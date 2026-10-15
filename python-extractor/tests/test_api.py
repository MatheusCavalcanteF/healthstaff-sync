"""Microsserviço FastAPI: POST /extracoes, GET /schema e GET /health."""

import json
from datetime import date

import pytest
from fastapi.testclient import TestClient

from api.app import create_app
from api.schema import (
    ARQUIVO_OPENAPI,
    ARQUIVO_SCHEMA,
    gerar_schema,
    openapi_formatado,
    schema_formatado,
)
from conftest import PaginaPdf, gerar_pdf
from models import LoteImportacao, ResultadoExtracao
from test_escala_excel import DIURNA, alternado, bloco, escala, pessoa
from test_ponto_csv import csv_texto
from test_ponto_excel import CABECALHO_BH, LINHAS_BH

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


@pytest.fixture
def cliente() -> TestClient:
    return TestClient(create_app(api_key="", tamanho_maximo_mb=1, ambiente="desenvolvimento"))


@pytest.fixture
def escala_xlsx(criar_xlsx) -> bytes:
    linhas = escala(*bloco(
        pessoa(1, 100007, "VANIA FICTICIA ROCHA", "Enfª Folguista Diurna", "12x36",
               "07:00 às 19:00", alternado("PN", "X")),
        pessoa(2, 100008, "RECEM CONTRATADA", "Téc. de Enf.", "12x36", "07:00 às 19:00",
               alternado("X", "PD")),
    ))
    return criar_xlsx({"Escala": linhas}, nome="escala.xlsx").read_bytes()


@pytest.fixture
def banco_horas_xlsx(criar_xlsx) -> bytes:
    return criar_xlsx({"Sheet1": [CABECALHO_BH, *LINHAS_BH]}, nome="bh.xlsx").read_bytes()


def enviar(cliente, dados=None, headers=None, **arquivos):
    files = {campo: valor for campo, valor in arquivos.items() if valor is not None}
    return cliente.post("/extracoes", files=files, data=dados or {}, headers=headers or {})


def colaboradores(lote: dict) -> dict[str, str | None]:
    return {c["nome"]: c["matricula"] for s in lote["setores"] for c in s["colaboradores"]}


# ---------------------------------------------------------------------------
# Infra e contrato
# ---------------------------------------------------------------------------


def test_health(cliente):
    assert cliente.get("/health").json() == {"status": "ok", "versao": "1.0.0"}


def test_schema_endpoint(cliente):
    resposta = cliente.get("/schema")
    assert resposta.status_code == 200
    schema = resposta.json()
    assert schema == gerar_schema()
    assert set(schema["properties"]) == {"escala", "bancoHoras"}
    assert "LoteImportacao" in schema["$defs"]


def test_schema_versionado_esta_atualizado():
    """Falha se o contrato mudou e o arquivo para o Java não foi regenerado
    (rode: python -m api.schema)."""
    assert ARQUIVO_SCHEMA.read_text(encoding="utf-8") == schema_formatado()


def test_openapi_versionado_esta_atualizado():
    """O java-backend gera as classes a partir deste arquivo
    (rode: python -m api.schema)."""
    assert ARQUIVO_OPENAPI.read_text(encoding="utf-8") == openapi_formatado()


def test_openapi_so_documenta_o_formato_de_erro_real(cliente):
    documento = cliente.get("/openapi.json").json()
    # A API nunca devolve o formato de validação padrão do FastAPI.
    assert "HTTPValidationError" not in documento["components"]["schemas"]
    for caminho in documento["paths"].values():
        for operacao in caminho.values():
            for status, resposta in operacao["responses"].items():
                if status != "200":
                    esquema = resposta["content"]["application/json"]["schema"]
                    assert esquema == {"$ref": "#/components/schemas/ErroApi"}


def test_openapi_documenta_erros(cliente):
    operacao = cliente.get("/openapi.json").json()["paths"]["/extracoes"]["post"]
    assert {"200", "401", "413", "422"} <= set(operacao["responses"])


# ---------------------------------------------------------------------------
# POST /extracoes
# ---------------------------------------------------------------------------


class TestExtracoes:
    def test_somente_escala(self, cliente, escala_xlsx):
        resposta = enviar(cliente, escala=("ESCALA OUTUBRO 2026.xlsx", escala_xlsx, XLSX))

        assert resposta.status_code == 200
        corpo = resposta.json()
        assert corpo["bancoHoras"] is None
        lote = corpo["escala"]
        assert lote["origem"]["tipoDocumento"] == "ESCALA_EXCEL"
        assert lote["origem"]["nomeArquivo"] == "ESCALA OUTUBRO 2026.xlsx"
        assert lote["periodoInicio"] == "2026-10-01"
        assert colaboradores(lote) == {"VANIA FICTICIA ROCHA": None, "RECEM CONTRATADA": None}
        ResultadoExtracao.model_validate(corpo)

    def test_somente_banco_de_horas(self, cliente, banco_horas_xlsx):
        resposta = enviar(
            cliente,
            {"data_referencia_ponto": "2026-10-01"},
            banco_horas=("TWO - Banco de Horas.xlsx", banco_horas_xlsx, XLSX),
        )
        assert resposta.status_code == 200
        lote = resposta.json()["bancoHoras"]
        assert lote["origem"]["tipoDocumento"] == "PONTO_EXCEL"
        assert lote["periodoInicio"] == "2026-10-01"
        assert lote["totalColaboradores"] == 5
        assert lote["inconsistencias"] == []

    def test_banco_de_horas_em_csv(self, cliente):
        conteudo = csv_texto(*LINHAS_BH).encode("cp1252")
        resposta = enviar(cliente, {"data_referencia_ponto": "2026-10-01"},
                          banco_horas=("bh.csv", conteudo, "text/csv"))
        assert resposta.status_code == 200
        assert resposta.json()["bancoHoras"]["origem"]["tipoDocumento"] == "PONTO_CSV"

    def test_escala_em_pdf(self, cliente):
        tabela = [["Matrícula", "Nome", *map(str, range(1, 32))], ["1", "Ana", "M", *[""] * 30]]
        pdf = gerar_pdf([PaginaPdf(["ESCALA OUTUBRO/2026", "Setor: UTI"], tabela, paisagem=True)])
        resposta = enviar(cliente, escala=("escala.pdf", pdf, "application/pdf"))
        assert resposta.status_code == 200
        assert resposta.json()["escala"]["origem"]["tipoDocumento"] == "ESCALA_PDF"

    def test_banco_de_horas_alimenta_matricula_da_escala(self, cliente, escala_xlsx, banco_horas_xlsx):
        resposta = enviar(
            cliente,
            {"data_referencia_ponto": "2026-10-01"},
            escala=("escala.xlsx", escala_xlsx, XLSX),
            banco_horas=("bh.xlsx", banco_horas_xlsx, XLSX),
        )
        assert resposta.status_code == 200
        escala_lote = resposta.json()["escala"]
        # Quem não está no Two continua na escala, sem matrícula e com aviso.
        assert colaboradores(escala_lote) == {"VANIA FICTICIA ROCHA": "90002", "RECEM CONTRATADA": None}
        avisos = [i for i in escala_lote["inconsistencias"] if i["codigo"] == "COLABORADOR_NAO_RECONHECIDO"]
        assert [i["nomeColaborador"] for i in avisos] == ["RECEM CONTRATADA"]

    def test_data_de_referencia_padrao_e_a_da_importacao(self, cliente, banco_horas_xlsx):
        resposta = enviar(cliente, banco_horas=("bh.xlsx", banco_horas_xlsx, XLSX))
        lote = resposta.json()["bancoHoras"]
        assert lote["periodoInicio"] == date.today().isoformat()
        (info,) = lote["inconsistencias"]
        assert info["severidade"] == "INFO"
        assert "data_referencia_ponto não informada" in info["mensagem"]

    def test_resposta_valida_no_contrato(self, cliente, escala_xlsx, banco_horas_xlsx):
        corpo = enviar(
            cliente,
            escala=("escala.xlsx", escala_xlsx, XLSX),
            banco_horas=("bh.xlsx", banco_horas_xlsx, XLSX),
        ).json()
        resultado = ResultadoExtracao.model_validate(corpo)
        assert isinstance(resultado.escala, LoteImportacao)
        assert "cpf" not in json.dumps(corpo).lower()

    def test_nome_de_arquivo_com_caminho_e_caracteres_invalidos(self, cliente, escala_xlsx):
        resposta = enviar(cliente, escala=('C:\\Users\\rh\\..\\esc:ala?.xlsx', escala_xlsx, XLSX))
        assert resposta.status_code == 200
        assert resposta.json()["escala"]["origem"]["nomeArquivo"] == "esc_ala_.xlsx"


class TestErros:
    def _erro(self, resposta, status, codigo, campo=None):
        assert resposta.status_code == status, resposta.text
        corpo = resposta.json()
        assert corpo["codigo"] == codigo
        assert corpo["campo"] == campo
        return corpo

    def test_nenhum_arquivo(self, cliente):
        self._erro(enviar(cliente), 422, "ARQUIVO_AUSENTE")

    def test_escala_enviada_no_campo_do_banco_de_horas(self, cliente, escala_xlsx):
        corpo = self._erro(
            enviar(cliente, banco_horas=("escala.xlsx", escala_xlsx, XLSX)),
            422, "TIPO_INCOMPATIVEL", "banco_horas",
        )
        assert "ESCALA_EXCEL" in corpo["mensagem"]
        assert corpo["arquivo"] == "escala.xlsx"

    def test_banco_de_horas_enviado_no_campo_da_escala(self, cliente, banco_horas_xlsx):
        self._erro(enviar(cliente, escala=("bh.xlsx", banco_horas_xlsx, XLSX)),
                   422, "TIPO_INCOMPATIVEL", "escala")

    @pytest.mark.parametrize(
        ("nome", "conteudo"),
        [
            ("planilha.xlsx", None),  # xlsx sem grade nem banco de horas
            ("foto.png", b"\x89PNG\r\n\x1a\n" + b"0" * 100),
            ("sem_extensao", b"Matricula;Saldo\n1;01:00\n"),
            ("corrompido.pdf", b"%PDF-1.4 isto nao e um pdf"),
        ],
    )
    def test_formato_nao_reconhecido(self, cliente, criar_xlsx, nome, conteudo):
        if conteudo is None:
            conteudo = criar_xlsx({"Dados": [["a", "b"], ["1", "2"]]}).read_bytes()
        corpo = self._erro(enviar(cliente, escala=(nome, conteudo, "application/octet-stream")),
                           422, "FORMATO_NAO_RECONHECIDO", "escala")
        assert corpo["arquivo"] == nome

    def test_extracao_falha_sem_competencia(self, cliente, criar_xlsx):
        linhas = escala(*bloco(DIURNA), titulo="ESCALA COLABORADORES - UTI")
        conteudo = criar_xlsx({"Escala": linhas}).read_bytes()
        corpo = self._erro(enviar(cliente, escala=("escala.xlsx", conteudo, XLSX)),
                           422, "EXTRACAO_FALHOU", "escala")
        assert "competência" in corpo["mensagem"]

    def test_arquivo_vazio(self, cliente):
        self._erro(enviar(cliente, escala=("escala.xlsx", b"", XLSX)), 422, "ARQUIVO_VAZIO", "escala")

    def test_arquivo_muito_grande(self, cliente):
        grande = b"0" * (1024 * 1024 + 1)  # limite do cliente de teste: 1 MB
        self._erro(enviar(cliente, escala=("escala.xlsx", grande, XLSX)),
                   413, "ARQUIVO_MUITO_GRANDE", "escala")

    def test_data_invalida(self, cliente, banco_horas_xlsx):
        self._erro(
            enviar(cliente, {"data_referencia_ponto": "31/10/2026"},
                   banco_horas=("bh.xlsx", banco_horas_xlsx, XLSX)),
            422, "REQUISICAO_INVALIDA", "data_referencia_ponto",
        )

    def test_erro_em_um_arquivo_nao_devolve_lote_parcial(self, cliente, escala_xlsx):
        # Escala válida + banco de horas inválido: a requisição inteira falha,
        # para o Java não persistir metade de uma importação.
        corpo = self._erro(
            enviar(cliente, escala=("escala.xlsx", escala_xlsx, XLSX),
                   banco_horas=("bh.csv", b"nome;telefone\nAna;1\n", "text/csv")),
            422, "FORMATO_NAO_RECONHECIDO", "banco_horas",
        )
        assert "escala" not in corpo


class TestAutenticacao:
    @pytest.fixture
    def protegido(self) -> TestClient:
        return TestClient(create_app(api_key="segredo-interno", tamanho_maximo_mb=1))

    def test_health_e_publico(self, protegido):
        assert protegido.get("/health").status_code == 200

    @pytest.mark.parametrize("headers", [{}, {"X-API-Key": "errada"}])
    def test_sem_chave_valida(self, protegido, escala_xlsx, headers):
        resposta = enviar(protegido, headers=headers, escala=("e.xlsx", escala_xlsx, XLSX))
        assert resposta.status_code == 401
        assert resposta.json()["codigo"] == "NAO_AUTORIZADO"
        assert protegido.get("/schema", headers=headers).status_code == 401

    def test_com_chave_valida(self, protegido, escala_xlsx):
        resposta = enviar(protegido, headers={"X-API-Key": "segredo-interno"},
                          escala=("e.xlsx", escala_xlsx, XLSX))
        assert resposta.status_code == 200

    def test_chave_lida_do_ambiente(self, monkeypatch):
        monkeypatch.setenv("EXTRACTOR_API_KEY", "do-ambiente")
        monkeypatch.setenv("EXTRACTOR_MAX_UPLOAD_MB", "5")
        cliente = TestClient(create_app())
        assert cliente.get("/schema").status_code == 401
        assert cliente.get("/schema", headers={"X-API-Key": "do-ambiente"}).status_code == 200


def test_exemplo_usado_pelo_java_backend_segue_o_contrato():
    """java-backend/src/test/resources/contrato/resultado-extracao.exemplo.json é
    uma resposta real desta API (dados fictícios) usada nos testes do cliente Java.
    Se o contrato mudar, regenere-o junto com o openapi.json."""
    exemplo = ARQUIVO_SCHEMA.parents[2] / "java-backend/src/test/resources/contrato/resultado-extracao.exemplo.json"
    resultado = ResultadoExtracao.model_validate_json(exemplo.read_text(encoding="utf-8"))
    assert resultado.escala is not None and resultado.banco_horas is not None


def test_avisa_quando_sobe_sem_autenticacao(monkeypatch, caplog):
    monkeypatch.setenv("EXTRACTOR_AMBIENTE", "desenvolvimento")
    monkeypatch.delenv("EXTRACTOR_API_KEY", raising=False)
    with caplog.at_level("WARNING", logger="api.app"):
        create_app()
    assert "autenticação desativada" in caplog.text

    caplog.clear()
    monkeypatch.setenv("EXTRACTOR_API_KEY", "segredo")
    with caplog.at_level("WARNING", logger="api.app"):
        create_app()
    assert "autenticação desativada" not in caplog.text


def test_main_aplica_a_chave_do_ambiente(monkeypatch):
    """O app servido pelo uvicorn (main:app) precisa respeitar EXTRACTOR_API_KEY."""
    import importlib

    # Antes do import: main.py cria o app ao ser importado, e sem chave não sobe.
    monkeypatch.setenv("EXTRACTOR_API_KEY", "do-ambiente")
    import main

    cliente = TestClient(importlib.reload(main).app)
    assert cliente.get("/schema").status_code == 401
    assert cliente.get("/schema", headers={"X-API-Key": "errada"}).status_code == 401
    assert cliente.get("/schema", headers={"X-API-Key": "do-ambiente"}).status_code == 200


class TestAmbiente:
    """Produção é o padrão (falha fechada): sem chave não sobe e a documentação
    interativa some. Só EXTRACTOR_AMBIENTE=desenvolvimento libera os dois."""

    @pytest.fixture(autouse=True)
    def ambiente_limpo(self, monkeypatch):
        monkeypatch.delenv("EXTRACTOR_AMBIENTE", raising=False)
        monkeypatch.delenv("EXTRACTOR_API_KEY", raising=False)

    def test_producao_sem_chave_nao_sobe(self):
        with pytest.raises(RuntimeError, match="obrigatória em produção"):
            create_app()

    def test_producao_sem_chave_nao_sobe_nem_por_parametro(self):
        with pytest.raises(RuntimeError, match="obrigatória em produção"):
            create_app(api_key="", ambiente="producao")

    def test_desenvolvimento_sem_chave_sobe(self, monkeypatch):
        monkeypatch.setenv("EXTRACTOR_AMBIENTE", "desenvolvimento")
        assert TestClient(create_app()).get("/health").status_code == 200

    @pytest.mark.parametrize("caminho", ["/docs", "/redoc", "/openapi.json"])
    def test_producao_sem_documentacao_interativa(self, caminho, monkeypatch):
        monkeypatch.setenv("EXTRACTOR_API_KEY", "segredo")
        cliente = TestClient(create_app())

        assert cliente.get(caminho).status_code == 404
        assert cliente.get("/health").status_code == 200

    @pytest.mark.parametrize("caminho", ["/docs", "/redoc", "/openapi.json"])
    def test_desenvolvimento_com_documentacao_interativa(self, caminho):
        cliente = TestClient(create_app(api_key="segredo", ambiente="desenvolvimento"))
        assert cliente.get(caminho).status_code == 200

    def test_ambiente_invalido_nao_sobe(self, monkeypatch):
        monkeypatch.setenv("EXTRACTOR_AMBIENTE", "homologacao")
        with pytest.raises(RuntimeError, match="EXTRACTOR_AMBIENTE inválido"):
            create_app(api_key="segredo")

    def test_contrato_gerado_nao_depende_do_ambiente(self):
        """python -m api.schema gera o openapi.json em desenvolvimento; o documento é o mesmo."""
        producao = create_app(api_key="segredo", ambiente="producao").openapi()
        desenvolvimento = create_app(api_key="segredo", ambiente="desenvolvimento").openapi()
        assert producao == desenvolvimento


class TestCadastroDoBackend:
    """Escala enviada sozinha: o java-backend manda as pessoas que já conhece."""

    def _cadastro(self, *pessoas) -> str:
        return json.dumps([{"matricula": m, "nome": n} for m, n in pessoas])

    def test_escala_sozinha_usa_o_cadastro_enviado(self, cliente, escala_xlsx):
        resposta = enviar(
            cliente,
            {"cadastro": self._cadastro(("90002", "VANIA FICTICIA ROCHA DOS SANTOS"))},
            escala=("escala.xlsx", escala_xlsx, XLSX),
        )
        assert resposta.status_code == 200
        lote = resposta.json()["escala"]
        # Nome da escala é parcial: o casamento aproximado só é possível com o cadastro.
        assert colaboradores(lote) == {"VANIA FICTICIA ROCHA": "90002", "RECEM CONTRATADA": None}

    def test_banco_de_horas_prevalece_sobre_o_cadastro(self, cliente, escala_xlsx, banco_horas_xlsx):
        resposta = enviar(
            cliente,
            {"data_referencia_ponto": "2026-10-01",
             "cadastro": self._cadastro(("1", "VANIA FICTICIA ROCHA"))},
            escala=("escala.xlsx", escala_xlsx, XLSX),
            banco_horas=("bh.xlsx", banco_horas_xlsx, XLSX),
        )
        assert resposta.status_code == 200
        # O Two (90002) e o cadastro (1) têm o mesmo nome: é ambíguo, ninguém é adivinhado.
        assert colaboradores(resposta.json()["escala"])["VANIA FICTICIA ROCHA"] is None

    def test_cadastro_vazio_equivale_a_nenhum(self, cliente, escala_xlsx):
        resposta = enviar(cliente, {"cadastro": "[]"}, escala=("escala.xlsx", escala_xlsx, XLSX))
        assert resposta.status_code == 200
        assert colaboradores(resposta.json()["escala"])["VANIA FICTICIA ROCHA"] is None

    @pytest.mark.parametrize(
        "invalido",
        ["isto não é json", '{"matricula": "1"}', '[{"matricula": "1"}]', '[{"matricula": "", "nome": "A"}]'],
    )
    def test_cadastro_invalido(self, cliente, escala_xlsx, invalido):
        resposta = enviar(cliente, {"cadastro": invalido}, escala=("escala.xlsx", escala_xlsx, XLSX))
        assert resposta.status_code == 422
        assert resposta.json()["codigo"] == "CADASTRO_INVALIDO"
        assert resposta.json()["campo"] == "cadastro"

    def test_cadastro_com_matricula_repetida_nao_quebra(self, cliente, escala_xlsx):
        cadastro = self._cadastro(("90002", "VANIA FICTICIA ROCHA"), ("90002", "VANIA FICTICIA ROCHA"))
        resposta = enviar(cliente, {"cadastro": cadastro}, escala=("escala.xlsx", escala_xlsx, XLSX))
        assert resposta.status_code == 200
