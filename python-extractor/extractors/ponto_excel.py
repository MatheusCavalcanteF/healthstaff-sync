"""Relatório "Banco de Horas" do Two exportado em Excel (.xlsx)."""

from pathlib import Path
from typing import ClassVar

from models import TipoDocumento
from utils.planilha import Aba, ler_planilha

from .ponto_base import PontoBancoHorasExtractor


class PontoExcelExtractor(PontoBancoHorasExtractor):
    nome = "ponto-two-excel"
    versao = "1.0.0"
    tipo_documento: ClassVar[TipoDocumento] = TipoDocumento.PONTO_EXCEL
    extensoes: ClassVar[frozenset[str]] = frozenset({".xlsx", ".xlsm"})

    def ler_abas(self, caminho: Path) -> list[Aba]:
        return ler_planilha(caminho)
