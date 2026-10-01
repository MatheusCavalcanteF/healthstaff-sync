"""GridExtractor lendo a escala em Excel no layout real da UTI Pediátrica.

As planilhas são sintéticas (nomes fictícios) mas reproduzem a estrutura do
arquivo real: título com setor e mês, cabeçalho em 3 linhas (rótulos / dias
da semana / dias) e em 2 linhas (rótulos + dias da semana / dias), vários
blocos, linhas de total, férias em células mescladas, licença sem término,
coluna de total de plantões, legenda e abas que não são escala.
"""

from datetime import date, datetime, time, timedelta

import pytest

from extractors import ErroExtracao, GridExtractor, criar_registry_padrao
from extractors.grid import interpretar_horario
from models import (
    CodigoInconsistencia,
    LoteImportacao,
    Severidade,
    TipoDocumento,
    TipoMarcacao,
    Turno,
)
from utils.matcher import ColaboradorMatcher
from utils.planilha import ler_planilha, texto_celula

TITULO = "ESCALA COLABORADORES - UTI PEDIÁTRICA - OUTUBRO  2026 "
ROTULOS = ["QUANT.", "COREN", "NOME DO COLABORADOR", "FUNÇÃO", "JORNADA", "HORÁRIO"]
SEMANA = ["QUIN", "SEX", "SAB", "DOM", "SEG", "TER ", "QUAR"]  # 01/10/2026 = quinta
PRIMEIRA_COLUNA_DIA = 7  # G
COLUNA_TOTAL = "AL"


def linha_semana() -> list:
    return [None] * 6 + [SEMANA[i % 7] for i in range(31)]


def linha_dias() -> list:
    return [None] * 6 + [float(d) for d in range(1, 32)]


def alternado(impar: str, par: str) -> dict[int, str]:
    return {d: impar if d % 2 else par for d in range(1, 32)}


def pessoa(quant, coren, nome, funcao, jornada, horario, dias: dict[int, str], total="auto") -> list:
    if total == "auto":
        total = sum(1 for v in dias.values() if v.strip().upper() in ("PD", "PN"))
    return [
        float(quant),
        float(coren) if coren else None,
        nome,
        funcao,
        jornada,
        horario,
        *(dias.get(d) for d in range(1, 32)),
        total,
    ]


def bloco(*pessoas, cabecalho_em_tres_linhas: bool = True) -> list[list]:
    if cabecalho_em_tres_linhas:
        return [ROTULOS, linha_semana(), linha_dias(), *pessoas]
    return [ROTULOS + linha_semana()[6:], linha_dias(), *pessoas]


def escala(*linhas, titulo: str | None = TITULO) -> list[list]:
    return [[], [titulo] if titulo else [], [], *linhas]


DIURNA = pessoa(1, 100002, "ELISA DIURNO", "Enfermeira Diurno Ímpar", "12x36", "07:00 às 19:00",
                alternado("PD", "X"))


def escala_completa() -> tuple[dict[str, list[list]], dict[str, list[str]]]:
    elisa = alternado("PD", "X") | {2: "FG", 3: "BH", 4: "AT", 5: "FA"}
    linhas = escala(
        # bloco 1: cabeçalho em 3 linhas (linhas 4-6)
        *bloco(
            pessoa(1, 100001, "ANA COORDENADORA", "COORD. ENFERMAGEM", "40H", "06:30 as 15:30",
                   alternado("PD", "X")),
            pessoa(2, None, "BRUNA FERIAS", "AUXILIAR ADMINISTRATIVO", "12x36", "07:00 as 19:00",
                   {1: "FÉRIAS A PARTIR DE  01/10/2026 ATÉ 30/10/2026"}, total=0),  # linha 8
            pessoa(3, None, "CAIO SOUZA", "AUX. ADMINISTRATIVO FOLGUISTA", "12x36",
                   "07:00 as 19:00", alternado("x", "PD")),
            pessoa(4, None, "DORA LIMA", "AUX. ADMINISTRATIVO FERISTA", "12x36",
                   "07:00 as 19:00", alternado("PD", "X")),
        ),
        [None] * 6 + [2] * 31,  # 11: totais do dia, sem rótulo
        [], [], [],
        # bloco 2: cabeçalho em 2 linhas (linhas 15-16)
        *bloco(
            pessoa(1, 100002, "ELISA DIURNO", "Enfermeira Diurno Ímpar", "12x36", "07:00 às 19:00",
                   elisa),
            pessoa(2, 100003, "FABIA FERIAS", "Enfermeira Diurno Par", "12x36", "07:00 às 19:00",
                   {1: "X", 2: "FÉRIAS A PARTIR DE 02/10/2026 ATÉ 31/10/2026"}, total=0),  # 18
            pessoa(3, 100004, "GABI NOTURNO", "Enfermeira Noturno Par", "12x36", "19:00 às 07:00",
                   alternado("X", "PN")),
            pessoa(4, 100005, "HELO TROCADA", "Téc. de Enf. Noturno ímpar", "12x36",
                   "07:00 às 19:00", alternado("PN", "X")),
            cabecalho_em_tres_linhas=False,
        ),
        ["TOTAL DIURNO"] + [None] * 5 + [1] * 31,
        [None, "TOTAL NOTURNO"] + [None] * 4 + [1] * 31,
        pessoa(5, 100006, "IRIS LICENCA", "Téc. de Enf. Noturno Ímpar", "12x36", "19:00 ás 07:00",
               {1: "LICENÇA GESTACIONAL A PARTIR 16/03/2026"}, total=None),  # 23
        [],
        [None, None, "LEGENDAS"],
        [None, None, "PD - PLANTÃO DIURNO"],
        [None, None, "FA - FALTA"],
    )
    abas = {
        "Escala": linhas,
        "Skybox": [
            [None, "QUANTITATIVO DE ENFERMEIROS"],
            [None, None, "QUANTITATIVO MENSAL DE ENFERMEIROS", 0.1333],
            [None, None, None, *(SEMANA[i % 7].lower() for i in range(31))],
            [None, "DIURNO", "ENFERMEIROS", *(float(d) for d in range(1, 32))],
        ],
        "Absenteísmo": [
            ["Técnicas de enfermagem"],
            [None, "PLANTÃO", "LM", "LS", "FJ", "FI"],
            ["FULANA", "12 x 36", 0, None, 2, 0],
        ],
    }
    mesclas = {"Escala": ["A2:AL2", "G8:AK8", "H18:AK18", "G23:AK23"]}
    return abas, mesclas


@pytest.fixture
def lote_completo(criar_xlsx) -> LoteImportacao:
    abas, mesclas = escala_completa()
    return GridExtractor().extrair(criar_xlsx(abas, nome="ESCALA OUTUBRO 2026.xlsx", mesclar=mesclas))


@pytest.fixture
def extrair_escala(criar_xlsx):
    def _extrair(linhas, extrator=None, mesclar=None, **abas_extras) -> LoteImportacao:
        caminho = criar_xlsx({"Escala": linhas, **abas_extras}, mesclar={"Escala": mesclar or []})
        return (extrator or GridExtractor()).extrair(caminho)

    return _extrair


def colaborador(lote: LoteImportacao, nome: str):
    return next(c for s in lote.setores for c in s.colaboradores if c.nome == nome)


def inconsistencias(lote: LoteImportacao, codigo: CodigoInconsistencia):
    return [i for i in lote.inconsistencias if i.codigo is codigo]


def plantoes(colab, tipo=TipoMarcacao.PLANTAO):
    return [p for p in colab.plantoes if p.tipo is tipo]


# ---------------------------------------------------------------------------
# Leitura de planilha
# ---------------------------------------------------------------------------


class TestPlanilha:
    @pytest.mark.parametrize(
        ("valor", "texto"),
        [
            (None, ""),
            (1.0, "1"),
            (100001.0, "100001"),
            (14, "14"),
            (0.1333, "0.1333"),
            ("  PD ", "  PD "),
            (datetime(2026, 10, 1), "01/10/2026"),
            (datetime(2026, 10, 1, 15, 15), "01/10/2026 15:15"),
            (date(2024, 1, 1), "01/01/2024"),
            (time(19, 0), "19:00"),
            (timedelta(hours=44, minutes=42), "44:42"),
            (timedelta(hours=-4, minutes=-3), "-4:03"),
        ],
    )
    def test_texto_celula(self, valor, texto):
        assert texto_celula(valor) == texto

    def test_le_todas_as_abas_com_largura_uniforme(self, criar_xlsx):
        abas = ler_planilha(criar_xlsx({"A": [["x"], ["a", "b", "c"]], "B": [[1.0]]}))
        assert [a.nome for a in abas] == ["A", "B"]
        assert abas[0].linhas == [["x", "", ""], ["a", "b", "c"]]
        assert abas[1].linhas == [["1"]]

    def test_celula_mesclada_mantem_valor_so_na_primeira(self, criar_xlsx):
        (aba,) = ler_planilha(criar_xlsx({"A": [["FÉRIAS", "x", "y"]]}, mesclar={"A": ["A1:C1"]}))
        assert aba.linhas == [["FÉRIAS", "", ""]]


@pytest.mark.parametrize(
    ("texto", "esperado"),
    [
        ("07:00 às 19:00", (time(7), 12)),
        ("07:00 ÁS 19:00", (time(7), 12)),
        ("07:00 ás 19:00", (time(7), 12)),
        ("06:30 as 15:30", (time(6, 30), 9)),
        ("19:00 às 07:00 ", (time(19), 12)),
        ("7h-13h", (time(7), 6)),
        ("12x36", None),
        ("", None),
    ],
)
def test_interpretar_horario(texto, esperado):
    resultado = interpretar_horario(texto)
    if esperado is None:
        assert resultado is None
    else:
        assert resultado == (esperado[0], timedelta(hours=esperado[1]))


# ---------------------------------------------------------------------------
# Escala completa (layout real)
# ---------------------------------------------------------------------------


class TestEscalaCompleta:
    def test_reconhecida_pelo_registry(self, criar_xlsx):
        abas, mesclas = escala_completa()
        caminho = criar_xlsx(abas, mesclar=mesclas)
        assert isinstance(criar_registry_padrao().selecionar(caminho), GridExtractor)

    def test_origem_competencia_e_setor(self, lote_completo):
        assert lote_completo.origem.tipo_documento is TipoDocumento.ESCALA_EXCEL
        assert lote_completo.origem.total_paginas is None
        assert (lote_completo.periodo_inicio, lote_completo.periodo_fim) == (
            date(2026, 10, 1), date(2026, 10, 31)
        )
        assert [s.nome for s in lote_completo.setores] == ["UTI PEDIÁTRICA"]

    def test_todos_os_blocos_e_somente_pessoas(self, lote_completo):
        nomes = [c.nome for s in lote_completo.setores for c in s.colaboradores]
        assert nomes == [
            "ANA COORDENADORA", "BRUNA FERIAS", "CAIO SOUZA", "DORA LIMA",
            "ELISA DIURNO", "FABIA FERIAS", "GABI NOTURNO", "HELO TROCADA", "IRIS LICENCA",
        ]

    def test_somente_inconsistencias_esperadas(self, lote_completo):
        codigos = sorted((i.codigo.value, i.severidade.value) for i in lote_completo.inconsistencias)
        assert codigos == sorted(
            [("COLABORADOR_SEM_MATRICULA", "AVISO")] * 9
            + [("LAYOUT_NAO_RECONHECIDO", "INFO")] * 2
        )
        abas_ignoradas = inconsistencias(lote_completo, CodigoInconsistencia.LAYOUT_NAO_RECONHECIDO)
        assert {i.aba for i in abas_ignoradas} == {"Skybox", "Absenteísmo"}

    def test_dados_fixos_da_linha(self, lote_completo):
        elisa = colaborador(lote_completo, "ELISA DIURNO")
        assert (elisa.registro_conselho, elisa.cargo, elisa.jornada) == (
            "100002", "Enfermeira Diurno Ímpar", "12x36"
        )
        assert colaborador(lote_completo, "BRUNA FERIAS").registro_conselho is None

    def test_siglas_da_legenda(self, lote_completo):
        elisa = colaborador(lote_completo, "ELISA DIURNO")
        por_dia = {p.data.day: p for p in elisa.plantoes}
        assert por_dia[1].turno is Turno.DIURNO_12H
        assert (por_dia[1].inicio, por_dia[1].fim) == (datetime(2026, 10, 1, 7), datetime(2026, 10, 1, 19))
        assert por_dia[2].tipo is TipoMarcacao.FOLGA and por_dia[2].codigo_original == "FG"
        assert por_dia[3].tipo is TipoMarcacao.FOLGA
        assert por_dia[3].observacao == "compensação de banco de horas"
        assert por_dia[4].tipo is TipoMarcacao.AFASTAMENTO
        assert por_dia[5].tipo is TipoMarcacao.FALTA
        assert por_dia[6].tipo is TipoMarcacao.FOLGA and por_dia[6].codigo_original == "X"

    def test_x_minusculo(self, lote_completo):
        caio = colaborador(lote_completo, "CAIO SOUZA")
        assert caio.plantoes[0].codigo_original == "x"
        assert caio.plantoes[0].tipo is TipoMarcacao.FOLGA

    def test_horario_da_coordenacao_substitui_padrao(self, lote_completo):
        (primeiro, *_) = plantoes(colaborador(lote_completo, "ANA COORDENADORA"))
        assert (primeiro.inicio, primeiro.fim) == (datetime(2026, 10, 1, 6, 30), datetime(2026, 10, 1, 15, 30))
        assert primeiro.turno is Turno.OUTRO
        assert primeiro.codigo_original == "PD"

    def test_pn_com_horario_diurno_mantem_19_07(self, lote_completo):
        (primeiro, *_) = plantoes(colaborador(lote_completo, "HELO TROCADA"))
        assert primeiro.turno is Turno.NOTURNO_12H
        assert (primeiro.inicio, primeiro.fim) == (datetime(2026, 10, 1, 19), datetime(2026, 10, 2, 7))

    def test_noturno(self, lote_completo):
        gabi = colaborador(lote_completo, "GABI NOTURNO")
        assert len(plantoes(gabi)) == 15
        assert all(p.turno is Turno.NOTURNO_12H and p.inicio.hour == 19 for p in plantoes(gabi))

    def test_ferias_mescladas_no_mes_inteiro(self, lote_completo):
        bruna = colaborador(lote_completo, "BRUNA FERIAS")
        assert [p.data.day for p in bruna.plantoes] == list(range(1, 31))  # até 30/10
        assert {p.tipo for p in bruna.plantoes} == {TipoMarcacao.FERIAS}

    def test_ferias_comecando_apos_folga(self, lote_completo):
        fabia = colaborador(lote_completo, "FABIA FERIAS")
        assert fabia.plantoes[0].tipo is TipoMarcacao.FOLGA
        ferias = plantoes(fabia, TipoMarcacao.FERIAS)
        assert [p.data.day for p in ferias] == list(range(2, 32))
        assert ferias[0].codigo_original.startswith("FÉRIAS A PARTIR")

    def test_licenca_sem_termino_ate_fim_do_mes(self, lote_completo):
        iris = colaborador(lote_completo, "IRIS LICENCA")
        assert len(iris.plantoes) == 31
        assert {p.tipo for p in iris.plantoes} == {TipoMarcacao.LICENCA}

    def test_folguista_e_ferista_pela_funcao(self, lote_completo):
        folguistas = {c.nome for s in lote_completo.setores for c in s.colaboradores if c.folguista}
        assert folguistas == {"CAIO SOUZA", "DORA LIMA"}
        assert colaborador(lote_completo, "CAIO SOUZA").cargo == "AUX. ADMINISTRATIVO FOLGUISTA"

    def test_totais_conferidos(self, lote_completo):
        assert not inconsistencias(lote_completo, CodigoInconsistencia.TOTAL_DIVERGENTE)
        # ANA 16, CAIO 15, DORA 16, ELISA 14 (BH e FA no lugar de 2 PD), GABI 15, HELO 16
        assert lote_completo.total_plantoes == 92

    def test_inconsistencias_localizadas_por_aba_e_linha(self, lote_completo):
        sem_matricula = inconsistencias(lote_completo, CodigoInconsistencia.COLABORADOR_SEM_MATRICULA)
        assert [(i.aba, i.linha) for i in sem_matricula][:2] == [("Escala", 7), ("Escala", 8)]
        assert all(i.pagina is None for i in sem_matricula)

    def test_json_valido(self, lote_completo):
        assert LoteImportacao.model_validate_json(lote_completo.to_json()) == lote_completo


# ---------------------------------------------------------------------------
# Casos específicos
# ---------------------------------------------------------------------------


class TestCasos:
    def test_total_divergente(self, extrair_escala):
        linha = pessoa(1, 1, "ANA", "Enf", "12x36", "07:00 às 19:00", alternado("PD", "X"), total=10)
        lote = extrair_escala(escala(*bloco(linha)))
        (inc,) = inconsistencias(lote, CodigoInconsistencia.TOTAL_DIVERGENTE)
        assert (inc.aba, inc.linha, inc.valor_bruto) == ("Escala", 7, "10")
        assert "(10)" in inc.mensagem and "(16)" in inc.mensagem

    def test_sem_coluna_de_total_nao_confere(self, extrair_escala):
        linha = pessoa(1, 1, "ANA", "Enf", "12x36", "07:00 às 19:00", alternado("PD", "X"), total=None)
        lote = extrair_escala(escala(*bloco(linha)))
        assert not inconsistencias(lote, CodigoInconsistencia.TOTAL_DIVERGENTE)

    def test_sigla_desconhecida_com_linha(self, extrair_escala):
        lote = extrair_escala(escala(*bloco(DIURNA[:6] + ["PD", "ZZ"] + [None] * 29 + [1])))
        (inc,) = inconsistencias(lote, CodigoInconsistencia.CODIGO_TURNO_DESCONHECIDO)
        assert (inc.aba, inc.linha, inc.data, inc.valor_bruto) == ("Escala", 7, date(2026, 10, 2), "ZZ")

    def test_periodo_fora_da_competencia(self, extrair_escala):
        linha = pessoa(1, 1, "ANA", "Enf", "12x36", "07:00 às 19:00",
                       {1: "FÉRIAS A PARTIR DE 01/11/2026 ATÉ 30/11/2026"}, total=0)
        lote = extrair_escala(escala(*bloco(linha)))
        assert colaborador(lote, "ANA").plantoes == []
        (inc,) = inconsistencias(lote, CodigoInconsistencia.DATA_INVALIDA)
        assert inc.severidade is Severidade.AVISO

    def test_periodo_invertido(self, extrair_escala):
        linha = pessoa(1, 1, "ANA", "Enf", "12x36", "07:00 às 19:00",
                       {1: "FÉRIAS DE 20/10/2026 ATÉ 10/10/2026"}, total=0)
        (inc,) = inconsistencias(extrair_escala(escala(*bloco(linha))), CodigoInconsistencia.DATA_INVALIDA)
        assert inc.severidade is Severidade.ERRO

    def test_ferias_com_termino_parcial(self, extrair_escala):
        dias = {d: "PD" for d in range(16, 32, 2)} | {1: "FÉRIAS 01/10/2026 A 15/10/2026"}
        lote = extrair_escala(escala(*bloco(pessoa(1, 1, "ANA", "Enf", "12x36", "07:00 às 19:00", dias))),
                              mesclar=["G7:U7"])
        ana = colaborador(lote, "ANA")
        assert [p.data.day for p in plantoes(ana, TipoMarcacao.FERIAS)] == list(range(1, 16))
        assert len(plantoes(ana)) == 8

    def test_ferias_sem_datas_vale_so_o_dia(self, extrair_escala):
        lote = extrair_escala(escala(*bloco(pessoa(1, 1, "ANA", "Enf", "12x36", "", {3: "FÉRIAS"}))))
        (ferias,) = colaborador(lote, "ANA").plantoes
        assert (ferias.data.day, ferias.tipo) == (3, TipoMarcacao.FERIAS)

    def test_horario_vazio_usa_padrao(self, extrair_escala):
        lote = extrair_escala(escala(*bloco(pessoa(1, 1, "ANA", "Enf", "12x36", "", {1: "PD"}))))
        (pd,) = colaborador(lote, "ANA").plantoes
        assert (pd.turno, pd.inicio.hour, pd.fim.hour) == (Turno.DIURNO_12H, 7, 19)

    def test_setor_nao_identificado_sem_titulo(self, extrair_escala):
        lote = extrair_escala(
            escala(*bloco(DIURNA), titulo="ESCALA OUTUBRO 2026")
        )
        assert lote.setores[0].nome == "NAO IDENTIFICADO"
        (inc,) = inconsistencias(lote, CodigoInconsistencia.SETOR_NAO_IDENTIFICADO)
        assert inc.aba == "Escala"

    def test_sem_competencia(self, extrair_escala):
        with pytest.raises(ErroExtracao, match="competência"):
            extrair_escala(escala(*bloco(DIURNA), titulo="ESCALA COLABORADORES - UTI"))

    def test_aba_seguinte_nao_herda_cabecalho(self, extrair_escala):
        # Linhas parecidas com colaboradores na aba seguinte não podem ser lidas
        # com o cabeçalho da aba "Escala".
        outra = [[None, None, "FULANA", None, None, None, *(["PD"] * 31)]]
        lote = extrair_escala(escala(*bloco(DIURNA)), Resumo=outra)
        assert [c.nome for s in lote.setores for c in s.colaboradores] == ["ELISA DIURNO"]

    def test_escala_em_segunda_aba(self, criar_xlsx):
        caminho = criar_xlsx({"Capa": [["Hospital"]], "Escala": escala(*bloco(DIURNA))})
        lote = GridExtractor().extrair(caminho)
        assert lote.total_colaboradores == 1

    def test_planilha_sem_grade_nao_reconhecida(self, criar_xlsx):
        caminho = criar_xlsx({"Dados": [["Matrícula", "Saldo"], ["1", "10:00"]]})
        with pytest.raises(ErroExtracao, match="não reconhece"):
            GridExtractor().extrair(caminho)

    def test_matricula_pelo_cadastro_do_two(self, extrair_escala):
        matcher = ColaboradorMatcher([("90001", "Elisa Diurno da Silva"), ("90006", "Outra Pessoa")])
        lote = extrair_escala(escala(*bloco(DIURNA)), extrator=GridExtractor(matcher=matcher))
        assert colaborador(lote, "ELISA DIURNO").matricula == "90001"


# ---------------------------------------------------------------------------
# Cadastro do Two não é barreira: ninguém da escala é descartado
# ---------------------------------------------------------------------------


class TestCadastroFlexivel:
    def _escala(self, *nomes):
        return escala(*bloco(*(
            pessoa(i, 1000 + i, nome, "Téc. de Enf.", "12x36", "07:00 às 19:00", alternado("PD", "X"))
            for i, nome in enumerate(nomes, 1)
        )))

    def _nomes(self, lote):
        return [c.nome for s in lote.setores for c in s.colaboradores]

    @pytest.mark.parametrize(
        "cadastro",
        [
            [],  # cadastro vazio
            [("90001", "ANA CLARA SOUZA")],  # só uma pessoa da escala
            [("1", "PESSOA SEM RELACAO"), ("2", "OUTRA PESSOA")],  # ninguém da escala
        ],
    )
    def test_mesmos_colaboradores_com_ou_sem_cadastro(self, extrair_escala, cadastro):
        linhas = self._escala("ANA CLARA SOUZA", "BIANCA RECEM CONTRATADA", "CARLA TEMPORARIA")
        sem_matcher = extrair_escala(linhas)
        com_matcher = extrair_escala(linhas, extrator=GridExtractor(matcher=ColaboradorMatcher(cadastro)))

        assert self._nomes(com_matcher) == self._nomes(sem_matcher)
        assert com_matcher.total_plantoes == sem_matcher.total_plantoes == 48

    def test_quem_nao_esta_no_two_fica_sem_matricula_com_aviso(self, extrair_escala):
        matcher = ColaboradorMatcher([("90001", "ANA CLARA SOUZA")])
        lote = extrair_escala(
            self._escala("ANA CLARA SOUZA", "BIANCA RECEM CONTRATADA"),
            extrator=GridExtractor(matcher=matcher),
        )

        assert colaborador(lote, "ANA CLARA SOUZA").matricula == "90001"
        bianca = colaborador(lote, "BIANCA RECEM CONTRATADA")
        assert bianca.matricula is None
        assert len(plantoes(bianca)) == 16
        (inc,) = inconsistencias(lote, CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO)
        assert (inc.severidade, inc.nome_colaborador, inc.linha) == (
            Severidade.AVISO, "BIANCA RECEM CONTRATADA", 8
        )
        assert not lote.possui_erros

    def test_nome_ambiguo_e_mantido(self, extrair_escala):
        matcher = ColaboradorMatcher([("1", "ANA COSTA"), ("2", "ANA COSTA")])
        lote = extrair_escala(self._escala("ANA COSTA"), extrator=GridExtractor(matcher=matcher))
        (ana,) = [c for s in lote.setores for c in s.colaboradores]
        assert ana.matricula is None
        assert len(plantoes(ana)) == 16

    def test_duas_pessoas_parecidas_com_o_mesmo_cadastro_nao_sao_mescladas(self, extrair_escala):
        # Só uma delas está no Two; a outra não pode herdar a matrícula nem
        # ser fundida como duplicata.
        matcher = ColaboradorMatcher([("90001", "MARIA SILVA OLIVEIRA")])
        lote = extrair_escala(
            self._escala("MARIA SILVA OLIVEIRA", "MARIA S OLIVEIRA"),
            extrator=GridExtractor(matcher=matcher),
        )

        assert {c.nome: c.matricula for s in lote.setores for c in s.colaboradores} == {
            "MARIA SILVA OLIVEIRA": "90001",
            "MARIA S OLIVEIRA": None,
        }
        assert not inconsistencias(lote, CodigoInconsistencia.COLABORADOR_DUPLICADO)
        (conflito,) = inconsistencias(lote, CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO)
        assert "já foi atribuída" in conflito.mensagem
        assert conflito.nome_colaborador == "MARIA S OLIVEIRA"

    def test_mesma_pessoa_em_dois_blocos_mantem_a_matricula(self, extrair_escala):
        matcher = ColaboradorMatcher([("90001", "ANA CLARA SOUZA")])
        linha = pessoa(1, 1, "ANA CLARA SOUZA", "Enf", "12x36", "07:00 às 19:00", {1: "PD"})
        outra = pessoa(1, 1, "ANA CLARA SOUZA", "Enf", "12x36", "07:00 às 19:00", {3: "PD"})
        lote = extrair_escala(escala(*bloco(linha), *bloco(outra)), extrator=GridExtractor(matcher=matcher))

        (ana,) = [c for s in lote.setores for c in s.colaboradores]
        assert ana.matricula == "90001"
        assert [p.data.day for p in ana.plantoes] == [1, 3]
        assert not inconsistencias(lote, CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO)
