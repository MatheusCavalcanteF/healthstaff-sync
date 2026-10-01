"""Relatório "Banco de Horas" do Two exportado em CSV.

Mesmas colunas da exportação Excel (ver ``ponto_base``). Aceita UTF-8 ou
Windows-1252, separador ";", tab, "," ou "|" e linhas de preâmbulo antes do
cabeçalho. Com separador ",", valores decimais ("44,700") devem vir entre
aspas, como qualquer exportador CSV faz.
"""

from pathlib import Path
from typing import ClassVar

from models import TipoDocumento
from utils.planilha import Aba, ler_csv

from .ponto_base import PontoBancoHorasExtractor


class PontoCsvExtractor(PontoBancoHorasExtractor):
    nome = "ponto-two-csv"
    versao = "2.0.0"
    tipo_documento: ClassVar[TipoDocumento] = TipoDocumento.PONTO_CSV
    extensoes: ClassVar[frozenset[str]] = frozenset({".csv", ".txt"})

    def ler_abas(self, caminho: Path) -> list[Aba]:
        return ler_csv(caminho)
