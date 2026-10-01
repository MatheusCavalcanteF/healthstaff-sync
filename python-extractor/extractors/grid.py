"""Extrator para escalas em grade mensal (colaborador x dias do mês).

Aceita PDF (tabelas com bordas) e Excel (.xlsx). Layout real da UTI
Pediátrica do Hospital Exemplo (aba "Escala"):

    ESCALA COLABORADORES - UTI PEDIÁTRICA - OUTUBRO 2026      <- setor e competência
    QUANT. | COREN | NOME DO COLABORADOR | FUNÇÃO | JORNADA | HORÁRIO |      |      | ...
           |       |                     |        |         |         | QUIN | SEX  | ...
           |       |                     |        |         |         | 1    | 2    | ... 31 |
    1      | 100002 | FULANA DE TAL       | Enf.   | 12x36   | 07:00 às 19:00 | PD | X | ... | 13 <- total
    2      | 100003 | CICLANA             | Enf.   | 12x36   | 07:00 às 19:00 | X  | FÉRIAS A PARTIR DE 02/10/2026 ATÉ 31/10/2026 (mesclada)
    TOTAL DIURNO   (linha de totais: ignorada)

Regras:
- Cabeçalho: coluna de nome + ao menos 7 dias consecutivos, na mesma linha ou
  em linhas seguintes (rótulos / dias da semana / números dos dias). Uma
  planilha pode ter vários blocos, cada um com seu cabeçalho.
- Colunas opcionais: matrícula, cargo/função, COREN, jornada, horário e a
  coluna de total de plantões logo após o último dia (conferida).
- Competência e setor: linha "Setor: X" ou título "ESCALA ... - SETOR - MÊS ANO".
- No PDF, páginas sem cabeçalho continuam a tabela da página anterior. No
  Excel, cada aba é independente; abas sem grade (ex.: "Skybox",
  "Absenteísmo") são ignoradas.
- O horário da linha ("06:30 às 15:30") substitui o horário padrão de PD/PN
  quando for do mesmo período (diurno/noturno).
- Células como "FÉRIAS A PARTIR DE 02/10/2026 ATÉ 31/10/2026" ou "LICENÇA
  GESTACIONAL A PARTIR 16/03/2026" geram a ausência para cada dia do período
  dentro da competência.
- Folguistas/feristas: linha de seção "FOLGUISTAS" ou a palavra FOLGUISTA /
  FERISTA no nome ou na função.
- Linha com nome mas sem matrícula e sem marcações é rótulo de seção:
  "FOLGUISTAS" abre a seção de folguistas e qualquer outro rótulo a encerra.
- O cadastro (``matcher``) nunca descarta ninguém: quem está na escala mas
  não no cadastro é exportado com matrícula nula e um AVISO
  (COLABORADOR_NAO_RECONHECIDO), assim como quem receberia por similaridade
  uma matrícula já atribuída a outro nome do mesmo documento.
"""

import calendar
import re
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from functools import cached_property
from pathlib import Path

import pdfplumber

from models import (
    CodigoInconsistencia,
    Colaborador,
    Plantao,
    Setor,
    Severidade,
    TipoDocumento,
    TipoMarcacao,
    Turno,
    normalizar_nome,
)
from utils.matcher import ColaboradorMatcher, StatusMatch, chave_nome
from utils.planilha import ler_planilha

from .base import BaseExtractor, ContextoExtracao, ErroExtracao

MIN_COLUNAS_DIA = 7
PAGINAS_DETECCAO = 3
MAX_LINHAS_CABECALHO = 3
SETOR_NAO_IDENTIFICADO = "NAO IDENTIFICADO"
EXTENSOES_EXCEL = frozenset({".xlsx", ".xlsm"})


@dataclass(frozen=True)
class DefinicaoSigla:
    tipo: TipoMarcacao
    turno: Turno | None = None
    inicio: time | None = None
    duracao: timedelta | None = None
    observacao: str | None = None

    def __post_init__(self) -> None:
        if self.tipo is TipoMarcacao.PLANTAO and (self.inicio is None or self.duracao is None):
            raise ValueError("sigla de plantão exige 'inicio' e 'duracao'")


def _turno(turno: Turno, hora: int, horas: int) -> DefinicaoSigla:
    return DefinicaoSigla(TipoMarcacao.PLANTAO, turno, time(hora), timedelta(hours=horas))


def _ausencia(tipo: TipoMarcacao, observacao: str | None = None) -> DefinicaoSigla:
    return DefinicaoSigla(tipo, observacao=observacao)


_MANHA = _turno(Turno.MANHA, 7, 6)
_TARDE = _turno(Turno.TARDE, 13, 6)
_NOITE = _turno(Turno.NOITE, 19, 12)
_NOTURNO_12H = _turno(Turno.NOTURNO_12H, 19, 12)
_DIURNO_12H = _turno(Turno.DIURNO_12H, 7, 12)
_PLANTAO_24H = _turno(Turno.PLANTAO_24H, 7, 24)
_FOLGA = _ausencia(TipoMarcacao.FOLGA)
_FERIAS = _ausencia(TipoMarcacao.FERIAS)
_LICENCA = _ausencia(TipoMarcacao.LICENCA)
_AFASTAMENTO = _ausencia(TipoMarcacao.AFASTAMENTO)
_FALTA = _ausencia(TipoMarcacao.FALTA)
_FOLGA_BANCO_HORAS = _ausencia(TipoMarcacao.FOLGA, "compensação de banco de horas")

# Chaves já normalizadas (maiúsculas, sem acento, só letras e dígitos).
SIGLAS_PADRAO: dict[str, DefinicaoSigla] = {
    # Códigos de turno de uma UTI Pediátrica (legenda da escala).
    "PD": _DIURNO_12H,  # plantão diurno 07:00-19:00
    "PN": _NOTURNO_12H,  # plantão noturno 19:00-07:00
    "FG": _FOLGA,  # folga mensal/quinzenal
    "BH": _FOLGA_BANCO_HORAS,  # folga referente a banco de horas
    "X": _FOLGA,  # folga regular
    "AT": _AFASTAMENTO,  # atestado
    "FA": _FALTA,  # falta
    # Códigos genéricos usados em outras escalas.
    "M": _MANHA,
    "T": _TARDE,
    "N": _NOITE,
    "SN": _NOTURNO_12H,
    "MT": _DIURNO_12H,
    "D": _DIURNO_12H,
    "SD": _DIURNO_12H,
    "P12": _DIURNO_12H,
    "P24": _PLANTAO_24H,
    "24H": _PLANTAO_24H,
    "F": _FOLGA,
    "FF": _FOLGA,
    "FOLGA": _FOLGA,
    "DSR": _FOLGA,
    "FE": _FERIAS,
    "FER": _FERIAS,
    "FERIAS": _FERIAS,
    "L": _LICENCA,
    "LIC": _LICENCA,
    "LICENCA": _LICENCA,
    "LM": _LICENCA,
    "LP": _LICENCA,
    "AF": _AFASTAMENTO,
    "ATESTADO": _AFASTAMENTO,
    "AFASTADO": _AFASTAMENTO,
    "AFASTAMENTO": _AFASTAMENTO,
    "FALTA": _FALTA,
}

AUSENCIAS_POR_PERIODO = {
    "FERIAS": TipoMarcacao.FERIAS,
    "LICENCA": TipoMarcacao.LICENCA,
    "ATESTADO": TipoMarcacao.AFASTAMENTO,
    "AFASTAMENTO": TipoMarcacao.AFASTAMENTO,
    "AFASTADO": TipoMarcacao.AFASTAMENTO,
}

MESES = {
    "JANEIRO": 1, "FEVEREIRO": 2, "MARCO": 3, "ABRIL": 4, "MAIO": 5, "JUNHO": 6,
    "JULHO": 7, "AGOSTO": 8, "SETEMBRO": 9, "OUTUBRO": 10, "NOVEMBRO": 11,
    "DEZEMBRO": 12,
}

_RE_MES_EXTENSO = re.compile(
    rf"\b({'|'.join(MESES)})\b\s*(?:DE\s*|/\s*|-\s*)?(\d{{4}})\b"
)
_RE_MES_ROTULADO = re.compile(
    r"\b(?:COMPETENCIA|MES|PERIODO|REFERENCIA|REF)\W*(\d{1,2})\s*/\s*(\d{4})\b"
)
_RE_MES_NUMERICO = re.compile(r"(?<![\d/])(\d{1,2})/(\d{4})\b")
_RE_SETOR = re.compile(
    r"^\s*(?:SETOR|UNIDADE|CL[IÍ]NICA|ALA)\s*[:\-]\s*(.+?)\s*$", re.IGNORECASE
)
_RE_TITULO = re.compile(r"^\s*ESCALA\b", re.IGNORECASE)
_RE_SEPARADOR_TITULO = re.compile(r"\s+[-–—]\s+")
_RE_HORARIO = re.compile(
    r"^(\d{1,2})(?:[:H](\d{2}))?H?\s*(?:-|/|AS|A)\s*(\d{1,2})(?:[:H](\d{2}))?H?$"
)
_RE_DIA = re.compile(r"^(\d{1,2})(?:\s+[A-Z]{1,3})?$")
_RE_TOTAL = re.compile(r"^\d{1,2}$")
_RE_DATA = re.compile(r"(\d{1,2})/(\d{1,2})/(\d{2,4})")
_RE_AUSENCIA_PERIODO = re.compile(rf"^({'|'.join(AUSENCIAS_POR_PERIODO)})\b")
_RE_FOLGUISTA = re.compile(r"\bFOLGUISTAS?\b|\bFERISTAS?\b|\(FOLG\.?\)")
_RE_MARCADOR_FOLGUISTA = re.compile(
    r"\s*[-–]?\s*\(?\s*(?:folguistas?|feristas?)\s*\)?\s*|\s*\(folg\.?\)\s*", re.IGNORECASE
)

_ROTULOS = {
    "matricula": {"MATRICULA", "MAT", "MATR", "REGISTRO", "RE", "CRACHA", "ID"},
    "coren": {"COREN", "CRM", "CONSELHO"},
    "cargo": {"CARGO", "FUNCAO", "CATEGORIA"},
    "jornada": {"JORNADA", "CARGA"},
    "horario": {"HORARIO", "TURNO"},
    "nome": {"NOME", "COLABORADOR", "FUNCIONARIO", "PROFISSIONAL", "SERVIDOR"},
}
_ROTULOS_TOTAL = {"TOTAL", "QTD", "QTDE", "PLANTOES"}


def _texto_celula(celula: str | None) -> str:
    return re.sub(r"\s+", " ", celula or "").strip()


def _normalizar(texto: str) -> str:
    return normalizar_nome(texto.replace("–", "-").replace("—", "-"))


def _chave_sigla(texto: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", _normalizar(texto))


def _interpretar_data(texto: str) -> date | None:
    dia, mes, ano = (int(g) for g in _RE_DATA.fullmatch(texto).groups())
    try:
        return date(ano + 2000 if ano < 100 else ano, mes, dia)
    except ValueError:
        return None


def interpretar_horario(texto: str) -> tuple[time, timedelta] | None:
    """'07:00 às 19:00', '7h-13h', '19:00–07:00' -> (início, duração)."""
    m = _RE_HORARIO.match(_normalizar(texto))
    if not m:
        return None
    h1, m1, h2, m2 = int(m[1]), int(m[2] or 0), int(m[3]), int(m[4] or 0)
    if h1 > 23 or h2 > 23 or m1 > 59 or m2 > 59:
        return None
    duracao = timedelta(hours=h2 - h1, minutes=m2 - m1)
    if duracao <= timedelta(0):
        duracao += timedelta(hours=24)
    return time(h1, m1), duracao


def _diurno(inicio: time) -> bool:
    return 5 <= inicio.hour < 18


# ----------------------------------------------------------------------
# Documento: PDF ou Excel apresentados como páginas com tabelas de texto
# ----------------------------------------------------------------------


class _PaginaPdf:
    continua_anterior = True
    aba = None

    def __init__(self, pagina, numero: int, table_settings: dict) -> None:
        self._pagina = pagina
        self.numero = numero
        self._table_settings = table_settings

    @cached_property
    def texto(self) -> str:
        return self._pagina.extract_text() or ""

    @cached_property
    def tabelas(self) -> list[list[list[str]]]:
        return [
            [[_texto_celula(c) for c in linha] for linha in tabela]
            for tabela in self._pagina.extract_tables(self._table_settings)
        ]

    def linha(self, indice: int) -> int | None:
        return None


@dataclass
class _PaginaExcel:
    numero: int
    aba: str
    linhas: list[list[str]]
    continua_anterior = False

    @cached_property
    def texto(self) -> str:
        return "\n".join(" ".join(c for c in linha if c) for linha in self.linhas)

    @cached_property
    def tabelas(self) -> list[list[list[str]]]:
        return [[[_texto_celula(c) for c in linha] for linha in self.linhas]]

    def linha(self, indice: int) -> int:
        return indice + 1  # cada aba é uma única tabela que começa na linha 1


@dataclass
class DocumentoGrade:
    paginas: list
    total_paginas: int | None
    excel: bool

    @property
    def paginas_deteccao(self) -> list:
        return self.paginas if self.excel else self.paginas[:PAGINAS_DETECCAO]


@dataclass(frozen=True)
class MapaColunas:
    n_colunas: int
    nome: int
    dias: dict[int, int]  # índice da coluna -> dia do mês
    matricula: int | None = None
    cargo: int | None = None
    coren: int | None = None
    jornada: int | None = None
    horario: int | None = None
    total: int | None = None


@dataclass
class _EstadoTabela:
    """Estado que atravessa tabelas (e páginas, no PDF) durante a extração."""

    mapa: MapaColunas | None = None
    secao_folguista: bool = False
    setor: str | None = None
    setores: dict[str, dict[str, Colaborador]] = field(default_factory=dict)
    # matrícula inferida pelo matcher -> nome (chave) a quem foi atribuída
    matriculas_inferidas: dict[str, str] = field(default_factory=dict)


def _analisar_cabecalho(celulas: list[str]) -> tuple[dict[str, int], dict[int, int]]:
    """Separa rótulos de colunas fixas e números de dias encontrados na linha."""
    rotulos: dict[str, int] = {}
    dias: dict[int, int] = {}
    for indice, celula in enumerate(celulas):
        texto = _normalizar(celula)
        if not texto:
            continue
        if m := _RE_DIA.match(texto):
            dias[indice] = int(m[1])
            continue
        tokens = set(re.sub(r"[^A-Z ]", " ", texto).split())
        for campo, nomes in _ROTULOS.items():
            if campo not in rotulos and tokens & nomes:
                rotulos[campo] = indice
                break
    return rotulos, dias


def _dias_validos(dias: dict[int, int]) -> bool:
    sequencia = list(dias.values())
    return (
        len(sequencia) >= MIN_COLUNAS_DIA
        and all(b == a + 1 for a, b in zip(sequencia, sequencia[1:]))
        and 1 <= sequencia[0] and sequencia[-1] <= 31
    )


def _montar_mapa(rotulos: dict[str, int], dias: dict[int, int], celulas: list[str]) -> MapaColunas:
    total = max(dias) + 1
    if total >= len(celulas) or total in rotulos.values():
        total = None
    elif celulas[total] and not set(_normalizar(celulas[total]).split()) & _ROTULOS_TOTAL:
        total = None
    return MapaColunas(n_colunas=len(celulas), dias=dias, total=total, **rotulos)


def localizar_cabecalhos(tabela: list[list[str]]) -> Iterator[tuple[int, MapaColunas, set[int]]]:
    """Encontra cabeçalhos de uma linha ou divididos em até 3 linhas.

    Gera (índice da linha em que o mapa passa a valer, mapa, linhas consumidas).
    """
    pendente: tuple[int, dict[str, int], list[str]] | None = None
    for indice, celulas in enumerate(tabela):
        rotulos, dias = _analisar_cabecalho(celulas)
        if "nome" in rotulos and _dias_validos(dias):
            pendente = None
            yield indice, _montar_mapa(rotulos, dias, celulas), {indice}
        elif "nome" in rotulos and not dias:
            pendente = (indice, rotulos, celulas)
        elif pendente and _dias_validos(dias) and "nome" not in rotulos:
            inicio, rotulos_pendentes, celulas_rotulos = pendente
            if not set(dias) & set(rotulos_pendentes.values()):
                largura = max(len(celulas), len(celulas_rotulos))
                yield indice, _montar_mapa(rotulos_pendentes, dias, celulas_rotulos + [""] * (largura - len(celulas_rotulos))), set(range(inicio, indice + 1))
            pendente = None
        elif pendente and indice - pendente[0] >= MAX_LINHAS_CABECALHO - 1:
            pendente = None


class GridExtractor(BaseExtractor):
    nome = "grade-mensal"
    versao = "2.0.0"
    extensoes = frozenset({".pdf"}) | EXTENSOES_EXCEL

    def __init__(
        self,
        matcher: ColaboradorMatcher | None = None,
        siglas: dict[str, DefinicaoSigla] | None = None,
        celula_vazia_como_folga: bool = False,
        table_settings: dict | None = None,
    ) -> None:
        self.matcher = matcher
        self.siglas = SIGLAS_PADRAO | {_chave_sigla(k): v for k, v in (siglas or {}).items()}
        self.celula_vazia_como_folga = celula_vazia_como_folga
        self.table_settings = table_settings or {}

    # ------------------------------------------------------------------
    # Interface BaseExtractor
    # ------------------------------------------------------------------

    def tipo_documento_de(self, caminho: Path) -> TipoDocumento:
        if caminho.suffix.lower() in EXTENSOES_EXCEL:
            return TipoDocumento.ESCALA_EXCEL
        return TipoDocumento.ESCALA_PDF

    @contextmanager
    def abrir(self, caminho: Path) -> Iterator[DocumentoGrade]:
        if caminho.suffix.lower() in EXTENSOES_EXCEL:
            abas = ler_planilha(caminho)
            paginas = [_PaginaExcel(i, aba.nome, aba.linhas) for i, aba in enumerate(abas, 1)]
            yield DocumentoGrade(paginas, None, excel=True)
            return
        with pdfplumber.open(caminho) as pdf:
            paginas = [
                _PaginaPdf(p, i, self.table_settings) for i, p in enumerate(pdf.pages, 1)
            ]
            yield DocumentoGrade(paginas, len(paginas), excel=False)

    def contar_paginas(self, documento: DocumentoGrade) -> int | None:
        return documento.total_paginas

    def pode_processar(self, documento: DocumentoGrade) -> bool:
        # Algumas escalas têm capa ou legenda antes da grade.
        return any(
            next(localizar_cabecalhos(tabela), None)
            for pagina in documento.paginas_deteccao
            for tabela in pagina.tabelas
        )

    def extrair_periodo(
        self, documento: DocumentoGrade, ctx: ContextoExtracao
    ) -> tuple[date, date]:
        competencia = self._identificar_competencia(documento)
        if competencia is None:
            raise ErroExtracao("competência (mês/ano) da escala não identificada")
        ano, mes = competencia
        return date(ano, mes, 1), date(ano, mes, calendar.monthrange(ano, mes)[1])

    def extrair_setores(
        self, documento: DocumentoGrade, ctx: ContextoExtracao
    ) -> list[Setor]:
        inicio, fim = self.extrair_periodo(documento, ctx)
        estado = _EstadoTabela()

        for pagina in documento.paginas:
            if not pagina.continua_anterior:
                estado.mapa, estado.secao_folguista, estado.setor = None, False, None
            estado.setor = self._identificar_setor(pagina.texto) or estado.setor

            for tabela in pagina.tabelas:
                self._processar_tabela(tabela, pagina, estado, inicio, fim, ctx)

        return [
            Setor(nome=nome, colaboradores=list(colaboradores.values()))
            for nome, colaboradores in estado.setores.items()
        ]

    # ------------------------------------------------------------------
    # Competência e setor
    # ------------------------------------------------------------------

    @staticmethod
    def _identificar_competencia(documento: DocumentoGrade) -> tuple[int, int] | None:
        for pagina in documento.paginas:
            texto = _normalizar(pagina.texto)
            if m := _RE_MES_EXTENSO.search(texto):
                return int(m[2]), MESES[m[1]]
            for padrao in (_RE_MES_ROTULADO, _RE_MES_NUMERICO):
                for m in padrao.finditer(texto):
                    mes, ano = int(m[1]), int(m[2])
                    if 1 <= mes <= 12:
                        return ano, mes
        return None

    @staticmethod
    def _identificar_setor(texto: str) -> str | None:
        linhas = texto.splitlines()
        for linha in linhas:
            if m := _RE_SETOR.match(linha):
                return m[1]
        # Título "ESCALA COLABORADORES - UTI PEDIÁTRICA - OUTUBRO 2026".
        for linha in linhas:
            if not _RE_TITULO.match(linha):
                continue
            for parte in _RE_SEPARADOR_TITULO.split(linha.strip())[1:]:
                normalizado = _normalizar(parte)
                if normalizado and not (
                    _RE_MES_EXTENSO.search(normalizado)
                    or _RE_MES_NUMERICO.search(normalizado)
                    or _RE_TITULO.match(normalizado)
                ):
                    return parte.strip()
        return None

    # ------------------------------------------------------------------
    # Linhas da tabela
    # ------------------------------------------------------------------

    def _processar_tabela(
        self,
        tabela: list[list[str]],
        pagina,
        estado: _EstadoTabela,
        inicio: date,
        fim: date,
        ctx: ContextoExtracao,
    ) -> None:
        ativacoes: dict[int, MapaColunas] = {}
        consumidas: set[int] = set()
        for indice, mapa, linhas in localizar_cabecalhos(tabela):
            ativacoes[indice] = mapa
            consumidas |= linhas

        if not ativacoes and estado.mapa is None:
            if any(any(linha) for linha in tabela):
                ctx.registrar(
                    CodigoInconsistencia.LAYOUT_NAO_RECONHECIDO,
                    f"aba '{pagina.aba}' sem grade de escala foi ignorada"
                    if pagina.aba
                    else "tabela sem cabeçalho de dias reconhecível foi ignorada",
                    Severidade.INFO if pagina.aba else Severidade.AVISO,
                    pagina=None if pagina.aba else pagina.numero,
                    aba=pagina.aba,
                    valor_bruto=" | ".join(c for c in tabela[0] if c)[:200] or None
                    if tabela
                    else None,
                )
            return

        for indice, celulas in enumerate(tabela):
            if indice in ativacoes:
                estado.mapa = ativacoes[indice]
                estado.secao_folguista = False
                continue
            if indice in consumidas or estado.mapa is None:
                continue  # rótulos do cabeçalho ou título antes da grade
            local = dict(
                pagina=None if pagina.aba else pagina.numero,
                aba=pagina.aba,
                linha=pagina.linha(indice),
            )
            if len(celulas) != estado.mapa.n_colunas:
                ctx.registrar(
                    CodigoInconsistencia.LAYOUT_NAO_RECONHECIDO,
                    f"linha com {len(celulas)} colunas, cabeçalho tem "
                    f"{estado.mapa.n_colunas}; linha ignorada",
                    setor=estado.setor,
                    valor_bruto=" | ".join(c for c in celulas if c)[:200] or None,
                    **local,
                )
                continue

            self._processar_linha(celulas, local, estado, inicio, fim, ctx)

    def _processar_linha(
        self,
        celulas: list[str],
        local: dict,
        estado: _EstadoTabela,
        inicio: date,
        fim: date,
        ctx: ContextoExtracao,
    ) -> None:
        mapa = estado.mapa

        def coluna(indice: int | None) -> str:
            return celulas[indice] if indice is not None else ""

        nome_bruto = coluna(mapa.nome)
        matricula = coluna(mapa.matricula)
        cargo = coluna(mapa.cargo)
        tem_marcacoes = any(celulas[i] for i in mapa.dias)

        if not nome_bruto:
            return  # dias da semana, totais, separadores ou linha vazia
        if not matricula and not tem_marcacoes:
            # Linha de seção ("FOLGUISTAS", "TÉCNICOS DE ENFERMAGEM", legenda...).
            estado.secao_folguista = bool(_RE_FOLGUISTA.search(_normalizar(nome_bruto)))
            return

        if estado.setor is None:
            estado.setor = SETOR_NAO_IDENTIFICADO
            ctx.registrar(
                CodigoInconsistencia.SETOR_NAO_IDENTIFICADO,
                "setor não encontrado no cabeçalho do documento",
                **{**local, "linha": None},
            )

        folguista = estado.secao_folguista or any(
            _RE_FOLGUISTA.search(_normalizar(t)) for t in (nome_bruto, cargo)
        )
        nome = _RE_MARCADOR_FOLGUISTA.sub(" ", nome_bruto).strip() or nome_bruto
        cargo = cargo if _RE_MARCADOR_FOLGUISTA.sub(" ", cargo).strip() else None
        horario = interpretar_horario(coluna(mapa.horario)) if mapa.horario is not None else None
        detalhes = dict(setor=estado.setor, nome_colaborador=nome, **local)

        matricula = self._resolver_matricula(nome, matricula or None, estado, ctx, detalhes)
        detalhes["matricula"] = matricula

        plantoes: list[Plantao] = []
        periodos: list[tuple[str, date]] = []
        for indice, dia in mapa.dias.items():
            texto = celulas[indice]
            try:
                data = date(inicio.year, inicio.month, dia)
            except ValueError:
                data = None
            if data is None or not inicio <= data <= fim:
                if texto:
                    ctx.registrar(
                        CodigoInconsistencia.DATA_INVALIDA,
                        f"dia {dia} não existe na competência {inicio:%m/%Y}",
                        Severidade.ERRO,
                        valor_bruto=texto,
                        **detalhes,
                    )
                continue
            if _RE_AUSENCIA_PERIODO.match(_normalizar(texto)) and _RE_DATA.search(texto):
                periodos.append((texto, data))
                continue
            plantoes.extend(self._converter_celula(texto, data, horario, ctx, detalhes))

        ocupados = {p.data for p in plantoes}
        for texto, data in periodos:
            plantoes.extend(
                self._expandir_periodo(texto, data, inicio, fim, ocupados, ctx, detalhes)
            )
        plantoes.sort(key=lambda p: (p.data, p.inicio or datetime.min))
        self._conferir_total(coluna(mapa.total), plantoes, ctx, detalhes)

        self._adicionar_colaborador(
            estado,
            Colaborador(
                matricula=matricula,
                nome=nome,
                cargo=cargo,
                registro_conselho=coluna(mapa.coren),
                jornada=coluna(mapa.jornada),
                folguista=folguista,
                plantoes=plantoes,
            ),
            ctx,
            detalhes,
        )

    def _expandir_periodo(
        self,
        texto: str,
        data_celula: date,
        inicio: date,
        fim: date,
        ocupados: set[date],
        ctx: ContextoExtracao,
        detalhes: dict,
    ) -> list[Plantao]:
        """'FÉRIAS A PARTIR DE 02/10/2026 ATÉ 31/10/2026' -> uma ausência por dia."""
        tipo = AUSENCIAS_POR_PERIODO[_RE_AUSENCIA_PERIODO.match(_normalizar(texto))[1]]
        datas = [_interpretar_data(m[0]) for m in _RE_DATA.finditer(texto)]
        de = datas[0]
        ate = datas[1] if len(datas) > 1 else fim  # "A PARTIR DE" sem término
        if de is None or ate is None or ate < de:
            ctx.registrar(
                CodigoInconsistencia.DATA_INVALIDA,
                f"período inválido em '{texto}'",
                Severidade.ERRO,
                data=data_celula,
                valor_bruto=texto,
                **detalhes,
            )
            return []
        de, ate = max(de, inicio), min(ate, fim)
        if de > ate:
            ctx.registrar(
                CodigoInconsistencia.DATA_INVALIDA,
                f"período de '{texto}' fora da competência {inicio:%m/%Y}",
                data=data_celula,
                valor_bruto=texto,
                **detalhes,
            )
            return []
        return [
            Plantao(data=dia, tipo=tipo, codigo_original=texto)
            for dia in (de + timedelta(days=n) for n in range((ate - de).days + 1))
            if dia not in ocupados
        ]

    @staticmethod
    def _conferir_total(
        texto: str, plantoes: list[Plantao], ctx: ContextoExtracao, detalhes: dict
    ) -> None:
        if not _RE_TOTAL.match(texto):
            return
        contados = len({p.data for p in plantoes if p.tipo is TipoMarcacao.PLANTAO})
        if int(texto) != contados:
            ctx.registrar(
                CodigoInconsistencia.TOTAL_DIVERGENTE,
                f"total de plantões informado na escala ({texto}) difere do "
                f"extraído ({contados})",
                valor_bruto=texto,
                **detalhes,
            )

    def _resolver_matricula(
        self,
        nome: str,
        matricula: str | None,
        estado: _EstadoTabela,
        ctx: ContextoExtracao,
        detalhes: dict,
    ) -> str | None:
        if matricula:
            if self.matcher and self.matcher.buscar_por_matricula(matricula) is None:
                ctx.registrar(
                    CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO,
                    f"matrícula {matricula} não consta no cadastro",
                    matricula=matricula,
                    **detalhes,
                )
            return matricula

        if self.matcher is None:
            ctx.registrar(
                CodigoInconsistencia.COLABORADOR_SEM_MATRICULA,
                f"'{nome}' sem matrícula na escala",
                **detalhes,
            )
            return None

        resultado = self.matcher.associar(nome)
        if resultado.encontrado:
            colaborador = resultado.colaborador
            dono = estado.matriculas_inferidas.setdefault(colaborador.matricula, chave_nome(nome))
            if dono != chave_nome(nome):
                # Duas pessoas da escala parecidas com o mesmo cadastro: não
                # atribui a mesma matrícula (seriam mescladas como duplicata).
                ctx.registrar(
                    CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO,
                    f"'{nome}' se parece com '{colaborador.nome}' "
                    f"({colaborador.matricula}, score {resultado.score:.0f}), mas essa "
                    f"matrícula já foi atribuída a outra pessoa da escala",
                    **detalhes,
                )
                return None
            ctx.registrar(
                CodigoInconsistencia.COLABORADOR_SEM_MATRICULA,
                f"matrícula {colaborador.matricula} inferida para '{nome}' "
                f"pelo cadastro '{colaborador.nome}' (score {resultado.score:.0f})",
                Severidade.INFO,
                matricula=colaborador.matricula,
                **detalhes,
            )
            return colaborador.matricula

        if resultado.status is StatusMatch.AMBIGUO:
            mensagem = f"'{nome}' é ambíguo no cadastro: {resultado.descrever_candidatos()}"
        elif resultado.candidatos:
            mensagem = (
                f"'{nome}' não encontrado no cadastro "
                f"(mais próximos: {resultado.descrever_candidatos()})"
            )
        else:
            mensagem = f"'{nome}' não encontrado no cadastro"
        ctx.registrar(CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO, mensagem, **detalhes)
        return None

    def _adicionar_colaborador(
        self,
        estado: _EstadoTabela,
        colaborador: Colaborador,
        ctx: ContextoExtracao,
        detalhes: dict,
    ) -> None:
        colaboradores = estado.setores.setdefault(estado.setor, {})
        chave = colaborador.matricula or f"nome:{chave_nome(colaborador.nome)}"
        existente = colaboradores.get(chave)
        if existente is None:
            colaboradores[chave] = colaborador
            return

        ctx.registrar(
            CodigoInconsistencia.COLABORADOR_DUPLICADO,
            f"'{colaborador.nome}' aparece mais de uma vez no setor; marcações mescladas",
            **detalhes,
        )
        existente.folguista = existente.folguista or colaborador.folguista
        vistos = {(p.data, p.codigo_original, p.inicio) for p in existente.plantoes}
        for plantao in colaborador.plantoes:
            if (plantao.data, plantao.codigo_original, plantao.inicio) in vistos:
                ctx.registrar(
                    CodigoInconsistencia.PLANTAO_DUPLICADO,
                    f"marcação '{plantao.codigo_original}' repetida em {plantao.data:%d/%m}",
                    **{**detalhes, "data": plantao.data, "valor_bruto": plantao.codigo_original},
                )
                continue
            existente.plantoes.append(plantao)
        existente.plantoes.sort(key=lambda p: (p.data, p.inicio or datetime.min))

    # ------------------------------------------------------------------
    # Siglas
    # ------------------------------------------------------------------

    def _converter_celula(
        self,
        texto: str,
        data: date,
        horario: tuple[time, timedelta] | None,
        ctx: ContextoExtracao,
        detalhes: dict,
    ) -> list[Plantao]:
        if not texto:
            if self.celula_vazia_como_folga:
                return [Plantao(data=data, tipo=TipoMarcacao.FOLGA, codigo_original="(vazio)")]
            return []

        definicoes = self.interpretar_sigla(texto)
        if definicoes is None:
            ctx.registrar(
                CodigoInconsistencia.CODIGO_TURNO_DESCONHECIDO,
                f"sigla '{texto}' não reconhecida",
                data=data,
                valor_bruto=texto,
                **detalhes,
            )
            return [Plantao(data=data, tipo=TipoMarcacao.DESCONHECIDO, codigo_original=texto)]

        plantoes = []
        for definicao in definicoes:
            if definicao.tipo is not TipoMarcacao.PLANTAO:
                plantoes.append(
                    Plantao(
                        data=data,
                        tipo=definicao.tipo,
                        codigo_original=texto,
                        observacao=definicao.observacao,
                    )
                )
                continue
            definicao = self._aplicar_horario(definicao, horario)
            inicio = datetime.combine(data, definicao.inicio)
            plantoes.append(
                Plantao(
                    data=data,
                    tipo=definicao.tipo,
                    turno=definicao.turno,
                    codigo_original=texto,
                    inicio=inicio,
                    fim=inicio + definicao.duracao,
                    observacao=definicao.observacao,
                )
            )
        return plantoes

    @staticmethod
    def _aplicar_horario(
        definicao: DefinicaoSigla, horario: tuple[time, timedelta] | None
    ) -> DefinicaoSigla:
        """Usa o horário da linha (ex.: coordenação 06:30-15:30) para plantões
        de 12h do mesmo período; PN de quem tem horário diurno mantém 19-07."""
        if (
            horario is None
            or definicao.turno not in (Turno.DIURNO_12H, Turno.NOTURNO_12H)
            or _diurno(horario[0]) != _diurno(definicao.inicio)
        ):
            return definicao
        inicio, duracao = horario
        turno = definicao.turno if duracao == definicao.duracao else Turno.OUTRO
        return DefinicaoSigla(definicao.tipo, turno, inicio, duracao, definicao.observacao)

    def interpretar_sigla(self, texto: str) -> list[DefinicaoSigla] | None:
        """Converte o conteúdo de uma célula em uma ou mais definições.

        Ordem: horário explícito ("07-19"), combinação ("M/T", "T+N"),
        sigla simples. Retorna ``None`` se não reconhecer.
        """
        normalizado = _normalizar(texto)

        if _RE_HORARIO.match(normalizado):
            horario = interpretar_horario(texto)
            if horario is None:
                return None
            return [DefinicaoSigla(TipoMarcacao.PLANTAO, Turno.OUTRO, *horario)]

        partes = [p for p in re.split(r"\s*[/+]\s*", normalizado) if p]
        if len(partes) > 1:
            definicoes = [self.siglas.get(_chave_sigla(p)) for p in partes]
            if all(definicoes):
                return definicoes

        definicao = self.siglas.get(_chave_sigla(normalizado))
        return [definicao] if definicao else None
