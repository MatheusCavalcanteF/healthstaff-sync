from datetime import date, datetime, time, timedelta

import pytest
from conftest import PaginaPdf

from extractors import (
    DefinicaoSigla,
    ErroExtracao,
    ExtratorNaoEncontrado,
    GridExtractor,
    criar_registry_padrao,
)
from models import (
    CodigoInconsistencia,
    LoteImportacao,
    Severidade,
    TipoMarcacao,
    Turno,
)
from utils.matcher import ColaboradorMatcher

CABECALHO_PADRAO = ["HOSPITAL SANTA CASA", "ESCALA MENSAL - OUTUBRO/2026", "Setor: UTI Adulto"]
COLUNAS_PADRAO = ("Matrícula", "Nome", "Cargo")

CADASTRO = [
    ("1001", "Maria da Silva Oliveira"),
    ("1002", "João Pedro Souza"),
    ("1003", "Ana Paula Costa"),
]


def montar_grade(
    linhas: list[tuple],
    dias: int = 31,
    colunas: tuple[str, ...] = COLUNAS_PADRAO,
    semana: bool = True,
    cabecalho: bool = True,
) -> list[list[str]]:
    """Cada linha: (*valores das colunas fixas, {dia: sigla})."""
    tabela = []
    if cabecalho:
        tabela.append([*colunas, *(str(d) for d in range(1, dias + 1))])
    if semana:
        tabela.append([""] * len(colunas) + list(("DSTQQSS" * 5)[:dias]))
    for *fixos, marcacoes in linhas:
        tabela.append([*fixos, *(marcacoes.get(d, "") for d in range(1, dias + 1))])
    return tabela


def pagina_grade(linhas: list[tuple], texto=CABECALHO_PADRAO, **kw) -> PaginaPdf:
    return PaginaPdf(linhas=list(texto), tabela=montar_grade(linhas, **kw), paisagem=True)


def colaborador(lote: LoteImportacao, nome: str, setor: int = 0):
    return next(c for c in lote.setores[setor].colaboradores if c.nome == nome)


def inconsistencias(lote: LoteImportacao, codigo: CodigoInconsistencia):
    return [i for i in lote.inconsistencias if i.codigo is codigo]


@pytest.fixture
def extrair(criar_pdf):
    """extrair(pagina | linhas, extrator=..., paginas_extras=...) -> LoteImportacao"""

    def _extrair(primeira, extrator=None, paginas_extras=None):
        if isinstance(primeira, list):
            primeira = pagina_grade(primeira)
        caminho = criar_pdf(primeira, paginas_extras=paginas_extras)
        return (extrator or GridExtractor()).extrair(caminho)

    return _extrair


# ---------------------------------------------------------------------------
# Detecção do layout
# ---------------------------------------------------------------------------


class TestDeteccao:
    def _pode(self, criar_pdf, pagina) -> bool:
        extrator = GridExtractor()
        with extrator.abrir(criar_pdf(pagina)) as documento:
            return extrator.pode_processar(documento)

    def test_reconhece_grade_mensal(self, criar_pdf):
        assert self._pode(criar_pdf, pagina_grade([("1", "Ana", "Enf", {1: "M"})]))

    def test_reconhece_grade_quinzenal_sem_matricula_e_cargo(self, criar_pdf):
        tabela = [["Colaborador", *map(str, range(16, 31))], ["Ana", *["M"] * 15]]
        assert self._pode(criar_pdf, PaginaPdf(CABECALHO_PADRAO, tabela))

    def test_rejeita_pdf_somente_texto(self, criar_pdf):
        assert not self._pode(criar_pdf, ["ESCALA MENSAL - OUTUBRO/2026", "Ana M T N"])

    def test_rejeita_tabela_sem_coluna_de_nome(self, criar_pdf):
        tabela = [["Matrícula", *map(str, range(1, 32))], ["1", *["M"] * 31]]
        assert not self._pode(criar_pdf, PaginaPdf(CABECALHO_PADRAO, tabela, paisagem=True))

    def test_rejeita_poucos_dias(self, criar_pdf):
        tabela = [["Nome", *map(str, range(1, 6))], ["Ana", *["M"] * 5]]
        assert not self._pode(criar_pdf, PaginaPdf(CABECALHO_PADRAO, tabela))

    def test_rejeita_dias_fora_de_sequencia(self, criar_pdf):
        dias = ["1", "2", "3", "5", "6", "7", "8", "9"]
        tabela = [["Nome", *dias], ["Ana", *["M"] * len(dias)]]
        assert not self._pode(criar_pdf, PaginaPdf(CABECALHO_PADRAO, tabela))

    def test_rejeita_tabela_de_outro_tipo(self, criar_pdf):
        tabela = [["Nome", "Telefone", "Ramal"], ["Ana", "1234", "56"]]
        assert not self._pode(criar_pdf, PaginaPdf(CABECALHO_PADRAO, tabela))


# ---------------------------------------------------------------------------
# Competência
# ---------------------------------------------------------------------------


class TestCompetencia:
    @pytest.mark.parametrize(
        ("titulo", "inicio", "fim"),
        [
            ("ESCALA MENSAL - OUTUBRO/2026", date(2026, 10, 1), date(2026, 10, 31)),
            ("Escala de Setembro de 2026", date(2026, 9, 1), date(2026, 9, 30)),
            ("ESCALA MARÇO 2027", date(2027, 3, 1), date(2027, 3, 31)),
            ("Competência: 02/2028", date(2028, 2, 1), date(2028, 2, 29)),
            ("Ref. 11/2026", date(2026, 11, 1), date(2026, 11, 30)),
            ("Emitido em 15/09/2026 - escala 10/2026", date(2026, 10, 1), date(2026, 10, 31)),
        ],
    )
    def test_identifica_competencia(self, extrair, titulo, inicio, fim):
        pagina = pagina_grade([("1", "Ana", "Enf", {1: "M"})], texto=[titulo, "Setor: UTI"])
        lote = extrair(pagina)
        assert (lote.periodo_inicio, lote.periodo_fim) == (inicio, fim)

    def test_sem_competencia_lanca_erro(self, extrair):
        pagina = pagina_grade([("1", "Ana", "Enf", {1: "M"})], texto=["ESCALA", "Setor: UTI"])
        with pytest.raises(ErroExtracao, match="competência"):
            extrair(pagina)


# ---------------------------------------------------------------------------
# Siglas
# ---------------------------------------------------------------------------


def _hora(h: int, m: int = 0) -> time:
    return time(h, m)


class TestSiglas:
    @pytest.mark.parametrize(
        ("sigla", "turno", "inicio", "horas"),
        [
            ("PD", Turno.DIURNO_12H, _hora(7), 12),
            ("PN", Turno.NOTURNO_12H, _hora(19), 12),
            ("M", Turno.MANHA, _hora(7), 6),
            ("t", Turno.TARDE, _hora(13), 6),
            ("N", Turno.NOITE, _hora(19), 12),
            ("SN", Turno.NOTURNO_12H, _hora(19), 12),
            ("S.N.", Turno.NOTURNO_12H, _hora(19), 12),
            ("MT", Turno.DIURNO_12H, _hora(7), 12),
            ("SD", Turno.DIURNO_12H, _hora(7), 12),
            ("P24", Turno.PLANTAO_24H, _hora(7), 24),
            ("07-19", Turno.OUTRO, _hora(7), 12),
            ("7h às 13h", Turno.OUTRO, _hora(7), 6),
            ("19:00–07:00", Turno.OUTRO, _hora(19), 12),
            ("07:30/13:30", Turno.OUTRO, _hora(7, 30), 6),
            ("07-07", Turno.OUTRO, _hora(7), 24),
        ],
    )
    def test_siglas_de_trabalho(self, sigla, turno, inicio, horas):
        (definicao,) = GridExtractor().interpretar_sigla(sigla)
        assert definicao.tipo is TipoMarcacao.PLANTAO
        assert definicao.turno is turno
        assert definicao.inicio == inicio
        assert definicao.duracao == timedelta(hours=horas)

    @pytest.mark.parametrize(
        ("sigla", "tipo"),
        [
            ("FG", TipoMarcacao.FOLGA),
            ("BH", TipoMarcacao.FOLGA),
            ("X", TipoMarcacao.FOLGA),
            ("x", TipoMarcacao.FOLGA),
            ("F", TipoMarcacao.FOLGA),
            ("Folga", TipoMarcacao.FOLGA),
            ("FOLGA", TipoMarcacao.FOLGA),
            ("DSR", TipoMarcacao.FOLGA),
            ("Férias", TipoMarcacao.FERIAS),
            ("FE", TipoMarcacao.FERIAS),
            ("LM", TipoMarcacao.LICENCA),
            ("Licença", TipoMarcacao.LICENCA),
            ("AT", TipoMarcacao.AFASTAMENTO),
            ("Atestado", TipoMarcacao.AFASTAMENTO),
        ],
    )
    def test_siglas_de_ausencia(self, sigla, tipo):
        (definicao,) = GridExtractor().interpretar_sigla(sigla)
        assert definicao.tipo is tipo
        assert definicao.inicio is None

    def test_folga_de_banco_de_horas_tem_observacao(self):
        (bh,) = GridExtractor().interpretar_sigla("BH")
        assert bh.observacao == "compensação de banco de horas"
        assert all(d.observacao is None for d in GridExtractor().interpretar_sigla("FG"))

    @pytest.mark.parametrize(
        ("sigla", "turnos"),
        [
            ("M/T", [Turno.MANHA, Turno.TARDE]),
            ("T + N", [Turno.TARDE, Turno.NOITE]),
        ],
    )
    def test_combinacoes(self, sigla, turnos):
        assert [d.turno for d in GridExtractor().interpretar_sigla(sigla)] == turnos

    @pytest.mark.parametrize("sigla", ["XYZ", "M/XYZ", "25-07", "07:75-13", "?"])
    def test_siglas_desconhecidas(self, sigla):
        assert GridExtractor().interpretar_sigla(sigla) is None

    def test_siglas_customizadas_complementam_e_sobrescrevem(self):
        extrator = GridExtractor(
            siglas={
                "p": DefinicaoSigla(TipoMarcacao.PLANTAO, Turno.DIURNO_12H, time(7), timedelta(hours=12)),
                "M": DefinicaoSigla(TipoMarcacao.PLANTAO, Turno.MANHA, time(6), timedelta(hours=6)),
            }
        )
        assert extrator.interpretar_sigla("P")[0].turno is Turno.DIURNO_12H
        assert extrator.interpretar_sigla("M")[0].inicio == time(6)
        assert GridExtractor().interpretar_sigla("M")[0].inicio == time(7)

    def test_definicao_de_plantao_exige_horario(self):
        with pytest.raises(ValueError):
            DefinicaoSigla(TipoMarcacao.PLANTAO, Turno.MANHA)


# ---------------------------------------------------------------------------
# Extração
# ---------------------------------------------------------------------------


class TestExtracao:
    def test_extracao_basica(self, extrair):
        lote = extrair(
            [
                ("1001", "Maria Silva Oliveira", "Enfermeira",
                 {1: "M", 2: "T", 3: "SN", 4: "F", 5: "Férias", 31: "N"}),
                ("1002", "João Pedro Souza", "Técnico", {1: "MT", 2: "P24"}),
            ]
        )

        assert [s.nome for s in lote.setores] == ["UTI Adulto"]
        maria = colaborador(lote, "Maria Silva Oliveira")
        assert (maria.matricula, maria.cargo, maria.folguista) == ("1001", "Enfermeira", False)
        assert [(p.data.day, p.tipo, p.turno) for p in maria.plantoes] == [
            (1, TipoMarcacao.PLANTAO, Turno.MANHA),
            (2, TipoMarcacao.PLANTAO, Turno.TARDE),
            (3, TipoMarcacao.PLANTAO, Turno.NOTURNO_12H),
            (4, TipoMarcacao.FOLGA, None),
            (5, TipoMarcacao.FERIAS, None),
            (31, TipoMarcacao.PLANTAO, Turno.NOITE),
        ]
        noturno = maria.plantoes[2]
        assert (noturno.inicio, noturno.fim) == (datetime(2026, 10, 3, 19), datetime(2026, 10, 4, 7))
        assert maria.plantoes[1].codigo_original == "T"
        assert maria.plantoes[4].codigo_original == "Férias"
        # N no último dia termina no mês seguinte; o lote continua válido.
        assert maria.plantoes[-1].fim == datetime(2026, 11, 1, 7)

        assert lote.total_colaboradores == 2
        assert lote.total_plantoes == 6
        assert lote.inconsistencias == []
        assert lote.origem.extrator == "grade-mensal"

    def test_codigos_da_uti_pediatrica(self, extrair):
        pagina = pagina_grade(
            [("2001", "Paula Reis", "Enfermeira", {1: "PD", 2: "PN", 3: "X", 4: "FG", 5: "BH"})],
            texto=["ESCALA MENSAL - OUTUBRO/2026", "Setor: UTI Pediátrica"],
        )
        lote = extrair(pagina)

        assert lote.inconsistencias == []
        (paula,) = lote.setores[0].colaboradores
        pd, pn, x, fg, bh = paula.plantoes
        assert (pd.turno, pd.inicio, pd.fim) == (
            Turno.DIURNO_12H, datetime(2026, 10, 1, 7), datetime(2026, 10, 1, 19)
        )
        assert (pn.turno, pn.inicio, pn.fim) == (
            Turno.NOTURNO_12H, datetime(2026, 10, 2, 19), datetime(2026, 10, 3, 7)
        )
        assert [p.tipo for p in (x, fg, bh)] == [TipoMarcacao.FOLGA] * 3
        assert [p.codigo_original for p in (x, fg, bh)] == ["X", "FG", "BH"]
        assert (x.observacao, fg.observacao) == (None, None)
        assert bh.observacao == "compensação de banco de horas"
        assert lote.total_plantoes == 2
        assert '"observacao":"compensação de banco de horas"' in lote.to_json()

    def test_celulas_vazias_e_linha_de_dias_da_semana_ignoradas(self, extrair):
        lote = extrair([("1001", "Ana", "Enf", {10: "M"})])
        (ana,) = lote.setores[0].colaboradores
        assert [p.data.day for p in ana.plantoes] == [10]

    def test_celula_vazia_como_folga(self, extrair):
        lote = extrair(
            pagina_grade([("1001", "Ana", "Enf", {1: "M"})], dias=31),
            extrator=GridExtractor(celula_vazia_como_folga=True),
        )
        (ana,) = lote.setores[0].colaboradores
        assert len(ana.plantoes) == 31
        assert sum(p.tipo is TipoMarcacao.FOLGA for p in ana.plantoes) == 30

    def test_combinacao_gera_dois_plantoes_no_mesmo_dia(self, extrair):
        lote = extrair([("1001", "Ana", "Enf", {7: "M/T"})])
        (ana,) = lote.setores[0].colaboradores
        assert [(p.inicio.hour, p.fim.hour) for p in ana.plantoes] == [(7, 13), (13, 19)]
        assert all(p.codigo_original == "M/T" for p in ana.plantoes)

    def test_sigla_desconhecida(self, extrair):
        lote = extrair([("1001", "Ana", "Enf", {1: "M", 2: "XYZ"})])

        (ana,) = lote.setores[0].colaboradores
        desconhecido = ana.plantoes[1]
        assert desconhecido.tipo is TipoMarcacao.DESCONHECIDO
        assert desconhecido.codigo_original == "XYZ"

        (inc,) = inconsistencias(lote, CodigoInconsistencia.CODIGO_TURNO_DESCONHECIDO)
        assert inc.severidade is Severidade.AVISO
        assert (inc.data, inc.valor_bruto, inc.pagina) == (date(2026, 10, 2), "XYZ", 1)
        assert (inc.matricula, inc.nome_colaborador, inc.setor) == ("1001", "Ana", "UTI Adulto")
        assert lote.total_plantoes == 1

    def test_dia_inexistente_na_competencia(self, extrair):
        pagina = pagina_grade(
            [("1001", "Ana", "Enf", {30: "M", 31: "T"}), ("1002", "Bia", "Enf", {1: "M"})],
            texto=["ESCALA NOVEMBRO/2026", "Setor: UTI"],
        )
        lote = extrair(pagina)

        ana = colaborador(lote, "Ana")
        assert [p.data for p in ana.plantoes] == [date(2026, 11, 30)]
        (inc,) = inconsistencias(lote, CodigoInconsistencia.DATA_INVALIDA)
        assert inc.severidade is Severidade.ERRO
        assert inc.valor_bruto == "T"
        assert lote.possui_erros

    def test_coluna_31_vazia_em_mes_de_30_dias_nao_gera_inconsistencia(self, extrair):
        pagina = pagina_grade(
            [("1001", "Ana", "Enf", {1: "M"})], texto=["ESCALA NOVEMBRO/2026", "Setor: UTI"]
        )
        assert extrair(pagina).inconsistencias == []

    def test_grade_parcial_quinzenal(self, extrair):
        tabela = [
            ["Nome", "Matrícula", *map(str, range(16, 31))],
            ["Ana", "1001", "M", *[""] * 13, "N"],
        ]
        lote = extrair(PaginaPdf(CABECALHO_PADRAO, tabela, paisagem=True))
        (ana,) = lote.setores[0].colaboradores
        assert ana.matricula == "1001"
        assert [p.data.day for p in ana.plantoes] == [16, 30]

    def test_lote_serializa_e_recarrega(self, extrair):
        lote = extrair([("1001", "Ana", "Enf", {1: "M", 2: "XYZ"})])
        assert LoteImportacao.model_validate_json(lote.to_json()) == lote


# ---------------------------------------------------------------------------
# Folguistas e matrícula
# ---------------------------------------------------------------------------


class TestFolguistas:
    def test_secao_de_folguistas(self, extrair):
        lote = extrair(
            [
                ("1001", "Ana", "Enf", {1: "M"}),
                ("", "FOLGUISTAS", "", {}),
                ("1002", "Bia", "Enf", {2: "T"}),
                ("1003", "Caio", "Téc", {3: "N"}),
            ]
        )
        assert [(c.nome, c.folguista) for c in lote.setores[0].colaboradores] == [
            ("Ana", False),
            ("Bia", True),
            ("Caio", True),
        ]

    def test_outro_rotulo_encerra_secao_de_folguistas(self, extrair):
        lote = extrair(
            [
                ("", "Folguistas", "", {}),
                ("1002", "Bia", "Enf", {2: "T"}),
                ("", "TÉCNICOS DE ENFERMAGEM", "", {}),
                ("1003", "Caio", "Téc", {3: "N"}),
            ]
        )
        assert [(c.nome, c.folguista) for c in lote.setores[0].colaboradores] == [
            ("Bia", True),
            ("Caio", False),
        ]
        assert lote.total_colaboradores == 2  # rótulos não viram colaboradores

    @pytest.mark.parametrize(
        "nome_bruto", ["Bia Lima (Folguista)", "Bia Lima - FOLGUISTA", "Bia Lima (FOLG.)"]
    )
    def test_marcador_no_nome(self, extrair, nome_bruto):
        lote = extrair([("1002", nome_bruto, "Enf", {2: "T"})])
        (bia,) = lote.setores[0].colaboradores
        assert (bia.nome, bia.folguista) == ("Bia Lima", True)

    def test_marcador_no_cargo(self, extrair):
        lote = extrair([("1002", "Bia", "Folguista", {2: "T"})])
        (bia,) = lote.setores[0].colaboradores
        assert bia.folguista is True
        assert bia.cargo is None

    def test_novo_cabecalho_encerra_secao(self, extrair):
        pagina2 = pagina_grade([("1003", "Caio", "Téc", {3: "N"})])
        lote = extrair(
            [("", "FOLGUISTAS", "", {}), ("1002", "Bia", "Enf", {2: "T"})],
            paginas_extras=[pagina2],
        )
        assert [(c.nome, c.folguista) for c in lote.setores[0].colaboradores] == [
            ("Bia", True),
            ("Caio", False),
        ]


class TestMatricula:
    def test_sem_matricula_e_sem_matcher(self, extrair):
        lote = extrair([("", "Bia Lima", "Enf", {2: "T"})])

        (bia,) = lote.setores[0].colaboradores
        assert bia.matricula is None
        (inc,) = inconsistencias(lote, CodigoInconsistencia.COLABORADOR_SEM_MATRICULA)
        assert inc.severidade is Severidade.AVISO
        assert inc.nome_colaborador == "Bia Lima"

    def test_layout_sem_coluna_de_matricula(self, extrair):
        pagina = pagina_grade([("Ana", {1: "M"}), ("Bia", {2: "T"})], colunas=("Nome",))
        lote = extrair(pagina)
        assert [c.matricula for c in lote.setores[0].colaboradores] == [None, None]
        assert len(inconsistencias(lote, CodigoInconsistencia.COLABORADOR_SEM_MATRICULA)) == 2

    def test_matricula_inferida_pelo_matcher(self, extrair):
        extrator = GridExtractor(matcher=ColaboradorMatcher(CADASTRO))
        lote = extrair([("", "MARIA S. OLIVEIRA", "Enf", {1: "M"})], extrator=extrator)

        (maria,) = lote.setores[0].colaboradores
        assert maria.matricula == "1001"
        assert maria.nome == "MARIA S. OLIVEIRA"  # nome do PDF preservado
        (inc,) = lote.inconsistencias
        assert inc.codigo is CodigoInconsistencia.COLABORADOR_SEM_MATRICULA
        assert inc.severidade is Severidade.INFO
        assert inc.matricula == "1001"
        assert "Maria da Silva Oliveira" in inc.mensagem

    def test_nome_nao_reconhecido_pelo_matcher(self, extrair):
        extrator = GridExtractor(matcher=ColaboradorMatcher(CADASTRO))
        lote = extrair([("", "Roberto Lima", "Enf", {1: "M"})], extrator=extrator)

        (roberto,) = lote.setores[0].colaboradores
        assert roberto.matricula is None
        (inc,) = lote.inconsistencias
        assert inc.codigo is CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO
        assert inc.severidade is Severidade.AVISO
        assert "não encontrado" in inc.mensagem

    def test_nome_ambiguo_no_cadastro(self, extrair):
        matcher = ColaboradorMatcher([("1", "Ana Costa"), ("2", "Ana Costa")])
        lote = extrair([("", "Ana Costa", "Enf", {1: "M"})], extrator=GridExtractor(matcher=matcher))

        assert lote.setores[0].colaboradores[0].matricula is None
        (inc,) = lote.inconsistencias
        assert inc.codigo is CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO
        assert "ambíguo" in inc.mensagem

    def test_matricula_fora_do_cadastro(self, extrair):
        extrator = GridExtractor(matcher=ColaboradorMatcher(CADASTRO))
        lote = extrair(
            [("1001", "Maria", "Enf", {1: "M"}), ("9999", "Zeca", "Enf", {1: "T"})],
            extrator=extrator,
        )
        (inc,) = lote.inconsistencias
        assert inc.codigo is CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO
        assert inc.matricula == "9999"
        assert colaborador(lote, "Zeca").matricula == "9999"


class TestDuplicidade:
    def test_colaborador_repetido_tem_marcacoes_mescladas(self, extrair):
        lote = extrair(
            [
                ("1001", "Ana", "Enf", {1: "M", 3: "T"}),
                ("1001", "Ana", "Enf", {2: "N", 3: "T"}),
            ]
        )
        (ana,) = lote.setores[0].colaboradores
        assert [p.data.day for p in ana.plantoes] == [1, 2, 3]
        assert len(inconsistencias(lote, CodigoInconsistencia.COLABORADOR_DUPLICADO)) == 1
        (dup,) = inconsistencias(lote, CodigoInconsistencia.PLANTAO_DUPLICADO)
        assert dup.data == date(2026, 10, 3)

    def test_sem_matricula_duplicidade_pelo_nome(self, extrair):
        lote = extrair([("", "Ana Lima", "", {1: "M"}), ("", "ANA LIMA", "", {2: "T"})])
        assert lote.total_colaboradores == 1
        assert inconsistencias(lote, CodigoInconsistencia.COLABORADOR_DUPLICADO)


# ---------------------------------------------------------------------------
# Múltiplas páginas e setores
# ---------------------------------------------------------------------------


class TestPaginas:
    def test_continuacao_sem_cabecalho_herda_setor_e_colunas(self, extrair):
        continuacao = PaginaPdf(
            linhas=["(continuação)"],
            tabela=montar_grade([("1002", "Bia", "Enf", {5: "T"})], cabecalho=False, semana=False),
            paisagem=True,
        )
        lote = extrair([("1001", "Ana", "Enf", {1: "M"})], paginas_extras=[continuacao])

        assert [s.nome for s in lote.setores] == ["UTI Adulto"]
        bia = colaborador(lote, "Bia")
        assert bia.plantoes[0].data == date(2026, 10, 5)
        assert lote.origem.total_paginas == 2

    def test_um_setor_por_pagina(self, extrair):
        pagina2 = pagina_grade(
            [("1002", "Bia", "Enf", {2: "T"})], texto=["ESCALA OUTUBRO/2026", "Setor: Pronto Socorro"]
        )
        lote = extrair([("1001", "Ana", "Enf", {1: "M"})], paginas_extras=[pagina2])

        assert [s.nome for s in lote.setores] == ["UTI Adulto", "Pronto Socorro"]
        assert colaborador(lote, "Bia", setor=1).matricula == "1002"

    def test_folguista_em_dois_setores_aparece_em_ambos(self, extrair):
        pagina2 = pagina_grade(
            [("", "FOLGUISTAS", "", {}), ("1009", "Rui", "Enf", {10: "SN"})],
            texto=["ESCALA OUTUBRO/2026", "Unidade: Clínica Médica"],
        )
        lote = extrair(
            [("", "FOLGUISTAS", "", {}), ("1009", "Rui", "Enf", {1: "M"})],
            paginas_extras=[pagina2],
        )
        ruis = [colaborador(lote, "Rui", setor=i) for i in (0, 1)]
        assert [s.nome for s in lote.setores] == ["UTI Adulto", "Clínica Médica"]
        assert all(r.folguista for r in ruis)
        assert not inconsistencias(lote, CodigoInconsistencia.COLABORADOR_DUPLICADO)

    def test_setor_nao_identificado(self, extrair):
        lote = extrair(pagina_grade([("1001", "Ana", "Enf", {1: "M"})], texto=["ESCALA OUTUBRO/2026"]))
        assert lote.setores[0].nome == "NAO IDENTIFICADO"
        (inc,) = inconsistencias(lote, CodigoInconsistencia.SETOR_NAO_IDENTIFICADO)
        assert inc.pagina == 1

    def test_tabela_sem_cabecalho_antes_de_qualquer_grade(self, extrair):
        avulsa = PaginaPdf(["ESCALA OUTUBRO/2026", "Setor: UTI"], [["Legenda", "M = Manhã"]])
        lote = extrair(avulsa, paginas_extras=[pagina_grade([("1001", "Ana", "Enf", {1: "M"})])])

        assert lote.total_colaboradores == 1
        (inc,) = inconsistencias(lote, CodigoInconsistencia.LAYOUT_NAO_RECONHECIDO)
        assert inc.pagina == 1


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------


class TestRegistryPadrao:
    def test_grid_registrado_no_registry_padrao(self):
        registry = criar_registry_padrao()
        assert "grade-mensal" in registry
        assert isinstance(registry.obter("grade-mensal"), GridExtractor)

    def test_registry_seleciona_e_extrai_grade(self, criar_pdf):
        caminho = criar_pdf(pagina_grade([("1001", "Ana", "Enf", {1: "M"})]))
        registry = criar_registry_padrao()

        assert isinstance(registry.selecionar(caminho), GridExtractor)
        lote = registry.extrair(caminho)
        assert lote.origem.extrator == "grade-mensal"
        assert lote.total_plantoes == 1

    def test_registry_repassa_matcher(self, criar_pdf):
        matcher = ColaboradorMatcher(CADASTRO)
        registry = criar_registry_padrao(matcher=matcher)
        assert registry.obter("grade-mensal").matcher is matcher

        caminho = criar_pdf(pagina_grade([("", "Joao P Souza", "Téc", {1: "M"})]))
        assert registry.extrair(caminho).setores[0].colaboradores[0].matricula == "1002"

    def test_registry_rejeita_pdf_que_nao_e_grade(self, criar_pdf):
        with pytest.raises(ExtratorNaoEncontrado):
            criar_registry_padrao().selecionar(criar_pdf(["Relatório qualquer"]))
