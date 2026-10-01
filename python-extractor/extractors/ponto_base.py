"""Lógica comum para o relatório "Banco de Horas" do sistema de ponto Two.

O mesmo relatório pode chegar em Excel (``PontoExcelExtractor``) ou CSV
(``PontoCsvExtractor``); as subclasses só mudam a leitura do arquivo em
linhas de texto (``ler_abas``). Layout real (exportação do Two):

    Matrícula | Colaborador | Gestor | Departamento | Empregador | Times | Data de início | Saldo | Saldo
    90001     | FULANA      | GESTORA| UTI PEDIATRICA HOSPITAL ...  | 59 - ...   |       | 01/01/24       | 44:42 | 44,700

- Todas as células vêm como texto. "Saldo" aparece duas vezes: em HH:MM (a
  hora pode ter 3 dígitos; "-" quando negativo) e em horas decimais com
  vírgula. O valor em HH:MM é o oficial; o decimal serve de conferência.
- "Data de início" é o início da apuração do banco (dd/mm/aa).
- O relatório não informa a data de emissão: o saldo é referido a
  ``data_referencia`` ou, se omitida, à data de modificação do arquivo
  (registrado como INFO no lote).
- O cabeçalho é localizado pelo nome das colunas (em qualquer aba, no
  Excel), em qualquer ordem, após até 30 linhas de preâmbulo. Outros relatórios do Two ("Apontamentos", "Banco de Horas
  Analítico") não têm coluna de saldo e não são reconhecidos.
- As batidas de entrada/saída não fazem parte de nenhuma exportação Excel do
  Two; estão apenas no espelho de ponto (PDF impresso como imagem).
"""

import re
from abc import ABC, abstractmethod
from contextlib import nullcontext
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

from models import (
    CodigoInconsistencia,
    Colaborador,
    SaldoBancoHoras,
    Setor,
    Severidade,
    normalizar_nome,
)
from utils.planilha import Aba

from .base import BaseExtractor, ContextoExtracao

MAX_LINHAS_PREAMBULO = 30
SETOR_NAO_IDENTIFICADO = "NAO IDENTIFICADO"
TOLERANCIA_CONFERENCIA_MINUTOS = 1

_ROTULOS = {
    "matricula": {"MATRICULA", "MAT", "MATR", "CHAPA", "CRACHA", "RE"},
    "inicio_apuracao": {"INICIO"},
    "setor": {"DEPARTAMENTO", "SETOR", "LOTACAO", "UNIDADE"},
    "nome": {"COLABORADOR", "NOME", "FUNCIONARIO", "EMPREGADO", "SERVIDOR"},
}

_RE_DATA = re.compile(r"(?<!\d)(\d{1,4})[/.-](\d{1,2})[/.-](\d{2,4})(?!\d)")
_RE_SALDO_HHMM = re.compile(r"^([+-])?(\d{1,5}):([0-5]\d)(?::\d{2})?([+-]|C|D)?$")
_RE_SALDO_DECIMAL = re.compile(r"^([+-])?(\d+(?:[.,]\d+)?)H?([+-]|C|D)?$")


def _tokens(texto: str) -> set[str]:
    return set(re.sub(r"[^A-Z0-9 ]", " ", normalizar_nome(texto)).split())


def interpretar_data(texto: str) -> date | None:
    """Aceita DD/MM/AAAA, DD/MM/AA, AAAA-MM-DD (com '/', '-' ou '.')."""
    m = _RE_DATA.search(texto)
    if not m:
        return None
    a, b, c = m.groups()
    try:
        if len(a) == 4:
            return date(int(a), int(b), int(c))
        if len(a) > 2:
            return None
        ano = int(c) + 2000 if len(c) == 2 else int(c)
        return date(ano, int(b), int(a))
    except ValueError:
        return None


def interpretar_saldo(texto: str) -> int | None:
    """Converte o saldo para minutos (negativo = débito).

    Formatos do Two: "44:42", "-04:03", "127:54", "44,700", "-9,6833".
    Também aceita "03:15-", "12:30 C/D" e "(03:15)".
    """
    bruto = re.sub(r"\s+", "", texto.upper())
    negativo_parenteses = bruto.startswith("(") and bruto.endswith(")")
    if negativo_parenteses:
        bruto = bruto[1:-1]

    if m := _RE_SALDO_HHMM.match(bruto):
        prefixo, horas, minutos, sufixo = m.groups()
        total = int(horas) * 60 + int(minutos)
    elif m := _RE_SALDO_DECIMAL.match(bruto):
        prefixo, valor, sufixo = m.groups()
        total = round(float(valor.replace(",", ".")) * 60)
    else:
        return None

    if prefixo and sufixo:
        return None  # sinal duplicado: ambíguo
    negativo = negativo_parenteses or prefixo == "-" or sufixo in ("-", "D")
    return -total if negativo else total


@dataclass(frozen=True)
class ColunasBancoHoras:
    matricula: int
    saldos: tuple[int, ...]  # o primeiro em HH:MM é o oficial; demais conferem
    nome: int | None = None
    setor: int | None = None
    inicio_apuracao: int | None = None


def mapear_cabecalho(celulas: list[str]) -> ColunasBancoHoras | None:
    indices: dict[str, int] = {}
    saldos: list[int] = []
    for i, celula in enumerate(celulas):
        tokens = _tokens(celula)
        if not tokens:
            continue
        if "SALDO" in tokens:
            saldos.append(i)
            continue
        for campo, rotulos in _ROTULOS.items():
            if campo not in indices and tokens & rotulos:
                indices[campo] = i
                break

    if "matricula" not in indices or not saldos:
        return None
    return ColunasBancoHoras(saldos=tuple(saldos), **indices)


@dataclass
class DocumentoBancoHoras:
    aba: Aba | None = None
    indice_cabecalho: int | None = None
    colunas: ColunasBancoHoras | None = None

    def registros(self):
        """Linhas de dados após o cabeçalho: (número da linha na planilha, células)."""
        if self.aba is None:
            return
        for indice in range(self.indice_cabecalho + 1, len(self.aba.linhas)):
            yield indice + 1, self.aba.linhas[indice]


def localizar_banco_horas(abas: list[Aba]) -> DocumentoBancoHoras:
    for aba in abas:
        for indice, linha in enumerate(aba.linhas[:MAX_LINHAS_PREAMBULO]):
            if colunas := mapear_cabecalho(linha):
                return DocumentoBancoHoras(aba, indice, colunas)
    return DocumentoBancoHoras()


class PontoBancoHorasExtractor(BaseExtractor, ABC):
    """Base dos extratores do relatório de Banco de Horas (Excel e CSV)."""

    def __init__(self, data_referencia: date | None = None) -> None:
        self.data_referencia = data_referencia

    # ------------------------------------------------------------------
    # Interface BaseExtractor
    # ------------------------------------------------------------------

    @abstractmethod
    def ler_abas(self, caminho: Path) -> list[Aba]:
        """Lê o arquivo como abas de linhas de texto."""

    def abrir(self, caminho: Path):
        return nullcontext(localizar_banco_horas(self.ler_abas(caminho)))

    def contar_paginas(self, documento: DocumentoBancoHoras) -> None:
        return None

    def pode_processar(self, documento: DocumentoBancoHoras) -> bool:
        return documento.colunas is not None

    def extrair_periodo(
        self, documento: DocumentoBancoHoras, ctx: ContextoExtracao
    ) -> tuple[date, date]:
        referencia = self._data_referencia(ctx.caminho)
        return referencia, referencia

    def extrair_setores(
        self, documento: DocumentoBancoHoras, ctx: ContextoExtracao
    ) -> list[Setor]:
        colunas = documento.colunas
        aba = documento.aba.nome or None  # CSV não tem abas
        referencia = self._data_referencia(ctx.caminho)
        if self.data_referencia is None:
            ctx.registrar(
                CodigoInconsistencia.OUTRO,
                f"relatório sem data de emissão; saldos referidos a {referencia:%d/%m/%Y} "
                f"(data de modificação do arquivo)",
                Severidade.INFO,
                aba=aba,
            )
        if colunas.setor is None:
            ctx.registrar(
                CodigoInconsistencia.SETOR_NAO_IDENTIFICADO,
                "relatório sem coluna de departamento",
                Severidade.INFO,
                aba=aba,
            )

        setores: dict[str, list[Colaborador]] = {}
        vistos: set[str] = set()
        for numero, celulas in documento.registros():
            if not any(celulas):
                continue
            colaborador = self._processar_linha(
                numero, celulas, colunas, aba, referencia, vistos, ctx
            )
            if colaborador is not None:
                setor = _celula(celulas, colunas.setor) or SETOR_NAO_IDENTIFICADO
                setores.setdefault(setor, []).append(colaborador)

        return [Setor(nome=nome, colaboradores=c) for nome, c in setores.items()]

    # ------------------------------------------------------------------
    # Linhas
    # ------------------------------------------------------------------

    def _processar_linha(
        self,
        numero: int,
        celulas: list[str],
        colunas: ColunasBancoHoras,
        aba: str | None,
        referencia: date,
        vistos: set[str],
        ctx: ContextoExtracao,
    ) -> Colaborador | None:
        matricula = _celula(celulas, colunas.matricula)
        nome = _celula(celulas, colunas.nome)
        local = dict(aba=aba, linha=numero, nome_colaborador=nome or None)

        if not matricula:
            ctx.registrar(
                CodigoInconsistencia.COLABORADOR_SEM_MATRICULA,
                f"linha {numero} sem matrícula foi ignorada",
                valor_bruto=" | ".join(c for c in celulas if c)[:200],
                **local,
            )
            return None
        if normalizar_nome(matricula).startswith(("TOTAL", "SUBTOTAL")):
            return None
        local["matricula"] = matricula
        local["setor"] = _celula(celulas, colunas.setor) or None

        if matricula in vistos:
            ctx.registrar(
                CodigoInconsistencia.COLABORADOR_DUPLICADO,
                f"matrícula {matricula} repetida no relatório; mantida a primeira linha",
                **local,
            )
            return None
        vistos.add(matricula)

        inicio_apuracao = None
        if texto_inicio := _celula(celulas, colunas.inicio_apuracao):
            inicio_apuracao = interpretar_data(texto_inicio)
            if inicio_apuracao is None:
                ctx.registrar(
                    CodigoInconsistencia.DATA_INVALIDA,
                    f"data de início do banco '{texto_inicio}' inválida",
                    valor_bruto=texto_inicio,
                    **local,
                )

        minutos = self._interpretar_saldos([_celula(celulas, i) for i in colunas.saldos], ctx, local)
        saldo = (
            None
            if minutos is None
            else SaldoBancoHoras(
                minutos=minutos, data_referencia=referencia, inicio_apuracao=inicio_apuracao
            )
        )
        return Colaborador(matricula=matricula, nome=nome or matricula, saldo_banco_horas=saldo)

    @staticmethod
    def _interpretar_saldos(textos: list[str], ctx: ContextoExtracao, local: dict) -> int | None:
        preenchidos = [t for t in textos if t]
        if not preenchidos:
            ctx.registrar(CodigoInconsistencia.SALDO_INVALIDO, "saldo não informado", **local)
            return None

        valores: list[tuple[str, int]] = []
        for texto in preenchidos:
            minutos = interpretar_saldo(texto)
            if minutos is None:
                ctx.registrar(
                    CodigoInconsistencia.SALDO_INVALIDO,
                    f"saldo '{texto}' não reconhecido",
                    valor_bruto=texto,
                    **local,
                )
            else:
                valores.append((texto, minutos))
        if not valores:
            return None

        oficial = next((v for v in valores if ":" in v[0]), valores[0])
        divergentes = [
            t for t, m in valores if abs(m - oficial[1]) > TOLERANCIA_CONFERENCIA_MINUTOS
        ]
        if divergentes:
            ctx.registrar(
                CodigoInconsistencia.SALDO_INVALIDO,
                f"colunas de saldo divergem: {oficial[0]} x {', '.join(divergentes)}; "
                f"usado {oficial[0]}",
                valor_bruto=" | ".join(preenchidos),
                **local,
            )
        return oficial[1]

    def _data_referencia(self, caminho: Path) -> date:
        if self.data_referencia is not None:
            return self.data_referencia
        return datetime.fromtimestamp(caminho.stat().st_mtime).date()


def _celula(celulas: list[str], indice: int | None) -> str:
    if indice is None or indice >= len(celulas):
        return ""
    return celulas[indice].strip()
