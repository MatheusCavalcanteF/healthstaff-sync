"""Limites de conteúdo (zip bomb em .xlsx, páginas de PDF), antes da extração."""

import io
import zipfile

import pytest
from fastapi.testclient import TestClient

from api.app import create_app
from conftest import gerar_pdf
from extractors import criar_registry_padrao
from utils import limites
from utils.limites import ArquivoForaDosLimites, verificar_limites

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def zip_bytes(entradas: dict[str, bytes]) -> bytes:
    memoria = io.BytesIO()
    with zipfile.ZipFile(memoria, "w", zipfile.ZIP_DEFLATED) as pacote:
        for nome, conteudo in entradas.items():
            pacote.writestr(nome, conteudo)
    return memoria.getvalue()


def bomba(megabytes: int) -> bytes:
    """Poucos KB no disco, ``megabytes`` MB descompactado."""
    return zip_bytes({"xl/worksheets/sheet1.xml": b"\0" * (megabytes * 1024 * 1024)})


class TestPlanilha:
    def test_planilha_normal_passa(self, criar_xlsx):
        verificar_limites(criar_xlsx({"Aba": [["a", "b"], ["c", "d"]]}, nome="normal.xlsx"))

    def test_recusa_entradas_demais(self, tmp_path, monkeypatch):
        monkeypatch.setattr(limites, "MAX_ENTRADAS_ZIP", 10)
        caminho = tmp_path / "muitas.xlsx"
        caminho.write_bytes(zip_bytes({f"xl/parte{i}.xml": b"x" for i in range(11)}))

        with pytest.raises(ArquivoForaDosLimites, match="11 entradas internas; o limite é 10"):
            verificar_limites(caminho)

    def test_recusa_zip_bomb(self, tmp_path, monkeypatch):
        monkeypatch.setattr(limites, "MAX_DESCOMPACTADO_MB", 1)
        caminho = tmp_path / "bomba.xlsx"
        caminho.write_bytes(bomba(3))
        assert caminho.stat().st_size < 50_000

        with pytest.raises(ArquivoForaDosLimites, match="3 MB descompactada; o limite é 1 MB"):
            verificar_limites(caminho)

    def test_vale_para_xlsm_e_maiusculas(self, tmp_path, monkeypatch):
        monkeypatch.setattr(limites, "MAX_DESCOMPACTADO_MB", 1)
        caminho = tmp_path / "BOMBA.XLSM"
        caminho.write_bytes(bomba(2))

        with pytest.raises(ArquivoForaDosLimites):
            verificar_limites(caminho)

    def test_zip_corrompido_fica_para_a_extracao(self, tmp_path):
        caminho = tmp_path / "quebrado.xlsx"
        caminho.write_bytes(b"isto nao e um zip")

        verificar_limites(caminho)  # não recusa: o openpyxl dá o motivo certo


class TestPdf:
    def test_no_limite_passa(self, tmp_path, monkeypatch):
        monkeypatch.setattr(limites, "MAX_PAGINAS_PDF", 3)
        caminho = tmp_path / "escala.pdf"
        caminho.write_bytes(gerar_pdf([["a"], ["b"], ["c"]]))

        verificar_limites(caminho)

    def test_recusa_paginas_demais(self, tmp_path, monkeypatch):
        monkeypatch.setattr(limites, "MAX_PAGINAS_PDF", 2)
        caminho = tmp_path / "escala.pdf"
        caminho.write_bytes(gerar_pdf([["a"], ["b"], ["c"]]))

        with pytest.raises(ArquivoForaDosLimites, match="mais de 2 páginas"):
            verificar_limites(caminho)

    def test_pdf_corrompido_fica_para_a_extracao(self, tmp_path):
        caminho = tmp_path / "quebrado.pdf"
        caminho.write_bytes(b"%PDF-1.4 isto nao e um pdf")

        verificar_limites(caminho)


def test_registry_recusa_antes_de_tentar_os_extratores(tmp_path, monkeypatch):
    """Sem isso, a recusa viraria "layout não reconhecido" (o registry trata
    exceção ao abrir como extrator incapaz)."""
    monkeypatch.setattr(limites, "MAX_PAGINAS_PDF", 1)
    caminho = tmp_path / "escala.pdf"
    caminho.write_bytes(gerar_pdf([["a"], ["b"]]))

    with pytest.raises(ArquivoForaDosLimites):
        criar_registry_padrao().selecionar(caminho)


def test_api_responde_413_com_o_motivo(monkeypatch):
    monkeypatch.setattr(limites, "MAX_DESCOMPACTADO_MB", 1)
    cliente = TestClient(create_app(api_key="", tamanho_maximo_mb=1, ambiente="desenvolvimento"))

    resposta = cliente.post("/extracoes", files={"escala": ("escala.xlsx", bomba(3), XLSX)})

    assert resposta.status_code == 413
    assert resposta.json() == {
        "codigo": "ARQUIVO_MUITO_GRANDE",
        "mensagem": "planilha com 3 MB descompactada; o limite é 1 MB",
        "campo": "escala",
        "arquivo": "escala.xlsx",
    }
