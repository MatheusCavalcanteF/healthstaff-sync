"""PontoCsvExtractor: relatório "Banco de Horas" do Two exportado em CSV.

Compartilha toda a interpretação com o PontoExcelExtractor (ponto_base); aqui
se testa o que é próprio do CSV: codificação, separador, preâmbulo e a
equivalência com a exportação Excel.
"""

import csv
import io
import os
from datetime import date, datetime

import pytest

from extractors import (
    ExtratorNaoEncontrado,
    PontoCsvExtractor,
    PontoExcelExtractor,
    criar_registry_padrao,
)
from models import CodigoInconsistencia, LoteImportacao, Severidade, TipoDocumento
from test_ponto_excel import CABECALHO_BH, LINHAS_BH, NEO, REFERENCIA, UTI_PED, bh
from utils.planilha import ler_csv


def csv_texto(*linhas, cabecalho=CABECALHO_BH, separador=";", preambulo=()) -> str:
    saida = io.StringIO()
    escritor = csv.writer(saida, delimiter=separador, lineterminator="\r\n")
    for linha in [*preambulo, cabecalho, *linhas]:
        escritor.writerow(["" if v is None else v for v in linha])
    return saida.getvalue()


@pytest.fixture
def criar_csv(tmp_path):
    def _criar(texto: str, nome: str = "TWO - Banco de Horas.csv", encoding: str = "utf-8"):
        caminho = tmp_path / nome
        caminho.write_bytes(texto.encode(encoding))
        return caminho

    return _criar


@pytest.fixture
def extrair(criar_csv):
    def _extrair(texto: str, **kw) -> LoteImportacao:
        return PontoCsvExtractor(data_referencia=REFERENCIA).extrair(criar_csv(texto, **kw))

    return _extrair


def colaborador(lote, matricula):
    return next(c for s in lote.setores for c in s.colaboradores if c.matricula == matricula)


def conteudo(lote: LoteImportacao) -> dict:
    """Dados do lote sem metadados do arquivo (origem, id, horário de geração)."""
    return lote.model_dump(include={"periodo_inicio", "periodo_fim", "setores", "inconsistencias"})


class TestLeituraCsv:
    @pytest.mark.parametrize("separador", [";", "\t", ",", "|"])
    def test_uma_interpretacao_por_separador(self, tmp_path, separador):
        caminho = tmp_path / "x.csv"
        caminho.write_text(f"a{separador}b\n1{separador}2\n", encoding="utf-8")
        abas = ler_csv(caminho)
        assert len(abas) == 4
        assert [["a", "b"], ["1", "2"]] in [a.linhas for a in abas]
        assert all(a.nome == "" for a in abas)

    def test_largura_uniforme(self, tmp_path):
        caminho = tmp_path / "x.csv"
        caminho.write_text("a;b;c\n1\n", encoding="utf-8")
        assert ler_csv(caminho)[0].linhas == [["a", "b", "c"], ["1", "", ""]]


class TestExtracaoCsv:
    def test_mesmo_resultado_que_o_excel(self, criar_csv, criar_xlsx):
        linhas = [*LINHAS_BH, bh("1", "SEM SALDO", NEO, "01/01/24", "", "")]
        via_csv = PontoCsvExtractor(data_referencia=REFERENCIA).extrair(criar_csv(csv_texto(*linhas)))
        via_excel = PontoExcelExtractor(data_referencia=REFERENCIA).extrair(
            criar_xlsx({"Sheet1": [CABECALHO_BH, *linhas]})
        )

        assert via_csv.origem.tipo_documento is TipoDocumento.PONTO_CSV
        assert via_excel.origem.tipo_documento is TipoDocumento.PONTO_EXCEL
        assert via_csv.origem.extrator == "ponto-two-csv"
        dados_excel = conteudo(via_excel)
        for inconsistencia in dados_excel["inconsistencias"]:
            inconsistencia["aba"] = None  # CSV não tem abas
        assert conteudo(via_csv) == dados_excel

    def test_saldos_e_setores(self, extrair):
        lote = extrair(csv_texto(*LINHAS_BH))
        assert [s.nome for s in lote.setores] == [UTI_PED, NEO]
        assert colaborador(lote, "90004").saldo_banco_horas.minutos == -243
        assert colaborador(lote, "90005").saldo_banco_horas.horas == 127.9
        assert lote.inconsistencias == []

    @pytest.mark.parametrize("encoding", ["utf-8", "utf-8-sig", "cp1252"])
    def test_codificacoes(self, extrair, encoding):
        lote = extrair(csv_texto(bh("1", "JOÃO DA CONCEIÇÃO", UTI_PED, "01/01/24", "01:00", "1,000")),
                       encoding=encoding)
        assert colaborador(lote, "1").nome == "JOÃO DA CONCEIÇÃO"

    @pytest.mark.parametrize("separador", [";", "\t", "|", ","])
    def test_separadores(self, extrair, separador):
        # Com "," o csv.writer coloca "44,700" entre aspas, como qualquer exportador.
        lote = extrair(csv_texto(*LINHAS_BH, separador=separador))
        assert lote.total_colaboradores == 5
        assert colaborador(lote, "90001").saldo_banco_horas.minutos == 2682
        assert lote.inconsistencias == []

    def test_decimal_sem_aspas_com_separador_virgula_e_sinalizado(self, extrair):
        texto = ",".join(CABECALHO_BH) + "\r\n1,ANA,G," + UTI_PED + ",E,,01/01/24,44:42,44,700\r\n"
        lote = extrair(texto)
        # A vírgula decimal desloca as colunas; o extrator não aceita calado.
        assert colaborador(lote, "1").saldo_banco_horas.minutos == 2682
        (inc,) = lote.inconsistencias
        assert inc.codigo is CodigoInconsistencia.SALDO_INVALIDO

    def test_preambulo(self, extrair):
        lote = extrair(csv_texto(*LINHAS_BH, preambulo=[["Relatório Banco de Horas"], ["Emitido em 01/10/2026"], []]))
        assert lote.total_colaboradores == 5

    def test_inconsistencias_com_linha_e_sem_aba(self, extrair):
        lote = extrair(csv_texto(LINHAS_BH[0], bh("2", "B", UTI_PED, "01/01/24", "10:00", "12,000")))
        (inc,) = lote.inconsistencias
        assert (inc.codigo, inc.linha, inc.aba, inc.matricula) == (
            CodigoInconsistencia.SALDO_INVALIDO, 3, None, "2"
        )

    def test_data_de_referencia_pela_modificacao_do_arquivo(self, criar_csv):
        caminho = criar_csv(csv_texto(*LINHAS_BH))
        instante = datetime(2026, 9, 30, 10).timestamp()
        os.utime(caminho, (instante, instante))
        lote = PontoCsvExtractor().extrair(caminho)
        assert lote.periodo_inicio == date(2026, 9, 30)
        assert [i.severidade for i in lote.inconsistencias] == [Severidade.INFO]

    def test_json_valido(self, extrair):
        lote = extrair(csv_texto(*LINHAS_BH))
        assert LoteImportacao.model_validate_json(lote.to_json()) == lote


class TestRegistryCsv:
    @pytest.mark.parametrize("nome", ["ponto.csv", "PONTO.CSV", "export.txt"])
    def test_csv_vai_para_o_extrator_csv(self, criar_csv, nome):
        caminho = criar_csv(csv_texto(*LINHAS_BH), nome=nome)
        registry = criar_registry_padrao(data_referencia_ponto=REFERENCIA)
        assert isinstance(registry.selecionar(caminho), PontoCsvExtractor)
        assert registry.extrair(caminho).total_colaboradores == 5

    def test_excel_continua_no_extrator_excel(self, criar_xlsx):
        caminho = criar_xlsx({"Sheet1": [CABECALHO_BH, *LINHAS_BH]})
        assert isinstance(criar_registry_padrao().selecionar(caminho), PontoExcelExtractor)

    @pytest.mark.parametrize(
        "texto",
        [
            "nome;telefone\nAna;1234\n",
            "Matrícula;Colaborador;Horas Trabalhadas\n1;Ana;07:00\n",  # Apontamentos
            "",
        ],
    )
    def test_csv_nao_reconhecido(self, criar_csv, texto):
        with pytest.raises(ExtratorNaoEncontrado):
            criar_registry_padrao().selecionar(criar_csv(texto))

    def test_arquivo_binario_com_extensao_csv(self, tmp_path):
        caminho = tmp_path / "ponto.csv"
        caminho.write_bytes(b"%PDF-1.4\x00\x01\xff\xfe lixo")
        with pytest.raises(ExtratorNaoEncontrado):
            criar_registry_padrao().selecionar(caminho)
