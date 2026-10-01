"""PontoExcelExtractor no layout real do relatório "Banco de Horas" do Two.

Planilhas sintéticas (dados fictícios) com a mesma estrutura das exportações
reais: todas as células como texto, "Saldo" duplicado (HH:MM e decimal com
vírgula), "Data de início" em dd/mm/aa.
"""

import os
from datetime import date, datetime

import pytest

from extractors import (
    ExtratorNaoEncontrado,
    GridExtractor,
    PontoExcelExtractor,
    criar_registry_padrao,
)
from extractors.ponto_base import interpretar_data, interpretar_saldo, mapear_cabecalho
from models import CodigoInconsistencia, LoteImportacao, Severidade, TipoDocumento
from utils.matcher import ColaboradorMatcher, StatusMatch

CABECALHO_BH = [
    "Matrícula", "Colaborador", "Gestor", "Departamento", "Empregador", "Times",
    "Data de início", "Saldo", "Saldo",
]
CABECALHO_APONTAMENTOS = [
    "Matrícula", "Colaborador", "Departamento", "Empregador", "Times",
    "Horas Trabalhadas", "Horas em pausas", "Horas Totais",
]
CABECALHO_ANALITICO = [
    "Matrícula", "Colaborador", "CPF", "Gestor", "Departamento", "Empregador",
    "Data", "Horas", "Horas", "Tipo", "Observações",
]
UTI_PED = "UTI PEDIATRICA HOSPITAL EXEMPLO"
NEO = "HX - UTI NEONATAL"
REFERENCIA = date(2026, 10, 1)


def bh(matricula, nome, departamento, inicio, hhmm, decimal) -> list:
    return [matricula, nome, "GESTORA EXEMPLO", departamento, "59 - HOSPITAL EXEMPLO", None,
            inicio, hhmm, decimal]


LINHAS_BH = [
    bh("90001", "TEREZA FICTICIA ALVES", UTI_PED, "01/01/24", "44:42", "44,700"),
    bh("90002", "VANIA FICTICIA ROCHA", UTI_PED, "01/01/24", "16:22", "16,367"),
    bh("90003", "WILMA FICTICIA PRADO", NEO, "01/01/24", "13:34", "13,566"),
    bh("90004", "YARA FICTICIA BRAGA", NEO, "16/05/25", "-04:03", "-4,050"),
    bh("90005", "ZILDA FICTICIA LIMA", UTI_PED, "01/01/24", "127:54", "127,900"),
]


@pytest.fixture
def criar_bh(criar_xlsx):
    def _criar(*linhas, cabecalho=CABECALHO_BH, preambulo=(), aba="Sheet1", nome="TWO - Banco de Horas.xlsx"):
        return criar_xlsx({aba: [*preambulo, cabecalho, *linhas]}, nome=nome)

    return _criar


@pytest.fixture
def extrair(criar_bh):
    def _extrair(*linhas, extrator=None, **kw) -> LoteImportacao:
        caminho = criar_bh(*(linhas or LINHAS_BH), **kw)
        return (extrator or PontoExcelExtractor(data_referencia=REFERENCIA)).extrair(caminho)

    return _extrair


def colaborador(lote: LoteImportacao, matricula: str):
    return next(c for s in lote.setores for c in s.colaboradores if c.matricula == matricula)


def inconsistencias(lote: LoteImportacao, codigo: CodigoInconsistencia):
    return [i for i in lote.inconsistencias if i.codigo is codigo]


# ---------------------------------------------------------------------------
# Interpretação de valores
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("texto", "minutos"),
    [
        # Formatos reais do Two
        ("44:42", 2682),
        ("-04:03", -243),
        ("127:54", 7674),
        ("00:00", 0),
        ("3,600", 216),
        ("-9,6833", -581),
        ("44,700", 2682),
        # Variações aceitas
        ("+12:30", 750),
        ("03:15-", -195),
        ("- 03:15", -195),
        ("(03:15)", -195),
        ("12:30 C", 750),
        ("12:30 D", -750),
        ("12:30:00", 750),
        ("2.25h", 135),
        ("abc", None),
        ("", None),
        ("+12:30-", None),
        ("12:75", None),
    ],
)
def test_interpretar_saldo(texto, minutos):
    assert interpretar_saldo(texto) == minutos


@pytest.mark.parametrize(
    ("texto", "esperado"),
    [
        ("01/01/24", date(2024, 1, 1)),
        ("16/05/25", date(2025, 5, 16)),
        ("16/05/2025", date(2025, 5, 16)),
        ("2025-05-16", date(2025, 5, 16)),
        ("31/02/25", None),
        ("ontem", None),
    ],
)
def test_interpretar_data(texto, esperado):
    assert interpretar_data(texto) == esperado


class TestCabecalho:
    def test_layout_banco_de_horas(self):
        colunas = mapear_cabecalho(CABECALHO_BH)
        assert (colunas.matricula, colunas.nome, colunas.setor, colunas.inicio_apuracao) == (0, 1, 3, 6)
        assert colunas.saldos == (7, 8)

    @pytest.mark.parametrize("cabecalho", [CABECALHO_APONTAMENTOS, CABECALHO_ANALITICO])
    def test_outros_relatorios_do_two_nao_sao_banco_de_horas(self, cabecalho):
        assert mapear_cabecalho(cabecalho) is None

    def test_gestor_e_empregador_nao_sao_confundidos_com_colaborador(self):
        colunas = mapear_cabecalho(["Gestor", "Empregador", "Matrícula", "Colaborador", "Saldo"])
        assert (colunas.nome, colunas.matricula) == (3, 2)


# ---------------------------------------------------------------------------
# Extração
# ---------------------------------------------------------------------------


class TestExtracao:
    def test_extracao_basica(self, extrair):
        lote = extrair()

        assert lote.origem.tipo_documento is TipoDocumento.PONTO_EXCEL
        assert lote.origem.extrator == "ponto-two-excel"
        assert lote.origem.total_paginas is None
        assert (lote.periodo_inicio, lote.periodo_fim) == (REFERENCIA, REFERENCIA)
        assert lote.inconsistencias == []
        assert lote.total_plantoes == 0
        assert [(s.nome, [c.matricula for c in s.colaboradores]) for s in lote.setores] == [
            (UTI_PED, ["90001", "90002", "90005"]),
            (NEO, ["90003", "90004"]),
        ]

    def test_saldos(self, extrair):
        lote = extrair()
        assert {c.matricula: c.saldo_banco_horas.minutos for s in lote.setores for c in s.colaboradores} == {
            "90001": 2682, "90002": 982, "90003": 814, "90004": -243, "90005": 7674,
        }
        saldo = colaborador(lote, "90004").saldo_banco_horas
        assert (saldo.horas, saldo.data_referencia, saldo.inicio_apuracao) == (
            -4.05, REFERENCIA, date(2025, 5, 16)
        )

    def test_nome_e_matricula_numerica(self, extrair):
        # No relatório real a matrícula vem como número no Excel.
        lote = extrair(bh(90001.0, "TEREZA FICTICIA ALVES", UTI_PED, "01/01/24", "44:42", "44,700"))
        adriana = colaborador(lote, "90001")
        assert adriana.nome == "TEREZA FICTICIA ALVES"

    def test_nao_exporta_dados_pessoais_extras(self, extrair):
        texto = extrair().to_json()
        assert "GESTORA EXEMPLO" not in texto
        assert "59 - HOSPITAL EXEMPLO" not in texto

    def test_arredondamento_do_decimal_dentro_da_tolerancia(self, extrair):
        # 13,566 h = 813,96 min ~ 13:34
        lote = extrair(bh("1", "A", UTI_PED, "01/01/24", "13:34", "13,566"))
        assert not inconsistencias(lote, CodigoInconsistencia.SALDO_INVALIDO)

    def test_colunas_de_saldo_divergentes(self, extrair):
        lote = extrair(bh("1", "A", UTI_PED, "01/01/24", "10:00", "12,000"))
        assert colaborador(lote, "1").saldo_banco_horas.minutos == 600  # HH:MM é o oficial
        (inc,) = inconsistencias(lote, CodigoInconsistencia.SALDO_INVALIDO)
        assert (inc.aba, inc.linha, inc.matricula) == ("Sheet1", 2, "1")
        assert inc.valor_bruto == "10:00 | 12,000"

    def test_somente_saldo_decimal(self, extrair):
        lote = extrair(bh("1", "A", UTI_PED, "01/01/24", "", "-1,500"))
        assert colaborador(lote, "1").saldo_banco_horas.minutos == -90

    def test_saldo_vazio(self, extrair):
        lote = extrair(bh("1", "A", UTI_PED, "01/01/24", "", ""))
        assert colaborador(lote, "1").saldo_banco_horas is None
        (inc,) = inconsistencias(lote, CodigoInconsistencia.SALDO_INVALIDO)
        assert "não informado" in inc.mensagem

    def test_saldo_ilegivel(self, extrair):
        lote = extrair(bh("1", "A", UTI_PED, "01/01/24", "##", "##"))
        assert colaborador(lote, "1").saldo_banco_horas is None
        assert len(inconsistencias(lote, CodigoInconsistencia.SALDO_INVALIDO)) == 2

    def test_data_de_inicio_invalida(self, extrair):
        lote = extrair(bh("1", "A", UTI_PED, "99/99/99", "01:00", "1,000"))
        assert colaborador(lote, "1").saldo_banco_horas.inicio_apuracao is None
        (inc,) = inconsistencias(lote, CodigoInconsistencia.DATA_INVALIDA)
        assert inc.severidade is Severidade.AVISO

    def test_linha_sem_matricula(self, extrair):
        lote = extrair(bh("", "SEM MATRICULA", UTI_PED, "01/01/24", "01:00", "1,000"), *LINHAS_BH[:1])
        (inc,) = inconsistencias(lote, CodigoInconsistencia.COLABORADOR_SEM_MATRICULA)
        assert (inc.linha, inc.nome_colaborador) == (2, "SEM MATRICULA")
        assert lote.total_colaboradores == 1

    def test_matricula_duplicada(self, extrair):
        lote = extrair(LINHAS_BH[0], bh("90001", "OUTRA", UTI_PED, "01/01/24", "00:10", "0,167"))
        assert colaborador(lote, "90001").saldo_banco_horas.minutos == 2682
        (inc,) = inconsistencias(lote, CodigoInconsistencia.COLABORADOR_DUPLICADO)
        assert inc.linha == 3

    def test_linhas_vazias_e_total_ignorados(self, extrair):
        lote = extrair(LINHAS_BH[0], [], ["Total", None, None, None, None, None, None, "300:00", "300,000"])
        assert lote.total_colaboradores == 1
        assert lote.inconsistencias == []

    def test_preambulo_antes_do_cabecalho(self, extrair):
        lote = extrair(*LINHAS_BH, preambulo=[["Relatório de Banco de Horas"], ["Emitido por: sistema"], []])
        assert lote.total_colaboradores == 5
        assert colaborador(lote, "90001").saldo_banco_horas.minutos == 2682

    def test_cabecalho_em_outra_aba(self, criar_xlsx):
        caminho = criar_xlsx({"Capa": [["Two"]], "Dados": [CABECALHO_BH, *LINHAS_BH]})
        lote = PontoExcelExtractor(data_referencia=REFERENCIA).extrair(caminho)
        assert lote.total_colaboradores == 5
        assert {i.aba for i in lote.inconsistencias} <= {"Dados"}

    def test_sem_departamento(self, extrair):
        lote = extrair(["1", "A", "05:00", "5,000"], cabecalho=["Matrícula", "Colaborador", "Saldo", "Saldo"])
        assert lote.setores[0].nome == "NAO IDENTIFICADO"
        (inc,) = inconsistencias(lote, CodigoInconsistencia.SETOR_NAO_IDENTIFICADO)
        assert inc.severidade is Severidade.INFO

    def test_data_de_referencia_pela_modificacao_do_arquivo(self, criar_bh):
        caminho = criar_bh(*LINHAS_BH)
        instante = datetime(2026, 9, 30, 15, 14).timestamp()
        os.utime(caminho, (instante, instante))

        lote = PontoExcelExtractor().extrair(caminho)

        assert lote.periodo_inicio == date(2026, 9, 30)
        assert colaborador(lote, "90001").saldo_banco_horas.data_referencia == date(2026, 9, 30)
        (inc,) = lote.inconsistencias
        assert inc.severidade is Severidade.INFO
        assert "30/09/2026" in inc.mensagem

    def test_json_valido(self, extrair):
        lote = extrair()
        assert LoteImportacao.model_validate_json(lote.to_json()) == lote

    def test_extrator_reutilizavel_com_colunas_em_outra_ordem(self, criar_bh):
        extrator = PontoExcelExtractor(data_referencia=REFERENCIA)
        invertido = list(reversed(range(len(CABECALHO_BH))))
        a = extrator.extrair(criar_bh(*LINHAS_BH, nome="a.xlsx"))
        b = extrator.extrair(
            criar_bh(
                [LINHAS_BH[3][i] for i in invertido],
                cabecalho=[CABECALHO_BH[i] for i in invertido],
                nome="b.xlsx",
            )
        )
        assert a.total_colaboradores == 5
        assert colaborador(b, "90004").saldo_banco_horas.minutos == -243
        assert b.inconsistencias == []


# ---------------------------------------------------------------------------
# Registry e integração escala x ponto
# ---------------------------------------------------------------------------


class TestRegistry:
    def test_registry_padrao(self):
        assert [e.nome for e in criar_registry_padrao()] == ["grade-mensal", "ponto-two-excel", "ponto-two-csv"]

    def test_repassa_data_de_referencia(self):
        registry = criar_registry_padrao(data_referencia_ponto=REFERENCIA)
        assert registry.obter("ponto-two-excel").data_referencia == REFERENCIA
        assert registry.obter("ponto-two-csv").data_referencia == REFERENCIA

    def test_banco_de_horas_vai_para_o_extrator_de_ponto(self, criar_bh):
        caminho = criar_bh(*LINHAS_BH)
        assert isinstance(criar_registry_padrao().selecionar(caminho), PontoExcelExtractor)

    @pytest.mark.parametrize(
        ("cabecalho", "linha"),
        [
            (CABECALHO_APONTAMENTOS, ["90007", "FULANA", UTI_PED, "59 - X", None, "07:24", "00:00", "07:24"]),
            (CABECALHO_ANALITICO, [90008, "FULANA", "000.000.000-00", "G", NEO, "59 - X", "15/06/25",
                                   "04:05", "4,0833", "Transf. folha", None]),
        ],
    )
    def test_outros_relatorios_do_two_nao_sao_reconhecidos(self, criar_bh, cabecalho, linha):
        with pytest.raises(ExtratorNaoEncontrado):
            criar_registry_padrao().selecionar(criar_bh(linha, cabecalho=cabecalho))

    def test_fluxo_banco_de_horas_alimenta_matricula_da_escala(self, criar_bh, criar_xlsx):
        from test_escala_excel import bloco, escala, pessoa, alternado

        registry = criar_registry_padrao(data_referencia_ponto=REFERENCIA)
        ponto = registry.extrair(criar_bh(*LINHAS_BH))
        matcher = ColaboradorMatcher.de_lote(ponto)

        linhas = escala(*bloco(
            pessoa(1, 100007, "VANIA FICTICIA ROCHA", "Enfª Folguista Diurna", "12x36",
                   "07:00 às 19:00", alternado("PN", "X")),
            pessoa(2, 1, "YARÁ FICTICIA DE BRAGA  ", "Téc. de Enf.", "12x36", "07:00 às 19:00",
                   alternado("X", "PD")),
            pessoa(3, 2, "RENATO FICTICIO RPA", "Téc. de Enf.", "12x36", "07:00 às 19:00",
                   alternado("PD", "X")),
        ))
        registry_escala = criar_registry_padrao(matcher=matcher)
        escala_lote = registry_escala.extrair(criar_xlsx({"Escala": linhas}, nome="ESCALA.xlsx"))

        assert {c.nome: c.matricula for s in escala_lote.setores for c in s.colaboradores} == {
            "VANIA FICTICIA ROCHA": "90002",
            "YARÁ FICTICIA DE BRAGA": "90004",
            "RENATO FICTICIO RPA": None,
        }
        (nao_reconhecido,) = inconsistencias(escala_lote, CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO)
        assert nao_reconhecido.nome_colaborador == "RENATO FICTICIO RPA"


class TestMatcherDeLote:
    def test_cadastro_a_partir_do_lote(self, extrair):
        matcher = ColaboradorMatcher.de_lote(extrair())
        assert len(matcher) == 5
        assert matcher.buscar_por_matricula("90002").nome == "VANIA FICTICIA ROCHA"
        assert matcher.associar("Vania Ficticia Rocha").status is StatusMatch.EXATO

    def test_ignora_colaboradores_sem_matricula_e_repetidos(self, criar_xlsx):
        from test_escala_excel import DIURNA, bloco, escala

        escala_lote = GridExtractor().extrair(criar_xlsx({"Escala": escala(*bloco(DIURNA))}))
        assert len(ColaboradorMatcher.de_lote(escala_lote)) == 0
