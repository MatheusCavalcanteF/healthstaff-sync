"""Limites de conteúdo dos arquivos, conferidos antes de qualquer leitura.

O tamanho do upload já é limitado (``EXTRACTOR_MAX_UPLOAD_MB``), mas isso não
basta: um ``.xlsx`` é um zip, e poucos MB podem descompactar em GB (zip bomb);
um PDF pequeno pode ter milhares de páginas. Os limites abaixo valem por
arquivo e podem ser ajustados por variável de ambiente.

Somar o tamanho *declarado* das entradas do zip é suficiente: o ``zipfile``
(usado pelo openpyxl) nunca descompacta além do tamanho declarado de uma
entrada; se o conteúdo real for maior, a leitura para ali e falha no CRC.
"""

import os
import zipfile
from pathlib import Path

from pdfminer.pdfpage import PDFPage


def _inteiro_do_ambiente(nome: str, padrao: int) -> int:
    valor = os.environ.get(nome)
    return int(valor) if valor else padrao


MAX_DESCOMPACTADO_MB = _inteiro_do_ambiente("EXTRACTOR_MAX_DESCOMPACTADO_MB", 200)
MAX_ENTRADAS_ZIP = _inteiro_do_ambiente("EXTRACTOR_MAX_ENTRADAS_ZIP", 1000)
MAX_PAGINAS_PDF = _inteiro_do_ambiente("EXTRACTOR_MAX_PAGINAS_PDF", 200)

EXTENSOES_ZIP = frozenset({".xlsx", ".xlsm"})


class ArquivoForaDosLimites(ValueError):
    """O arquivo passa de um limite de conteúdo (a API responde 413)."""


def verificar_limites(caminho: str | Path) -> None:
    """Recusa planilhas grandes demais descompactadas e PDFs com páginas demais.

    Arquivo corrompido não é recusado aqui: a extração dá o motivo certo.
    """
    caminho = Path(caminho)
    sufixo = caminho.suffix.lower()
    if sufixo in EXTENSOES_ZIP:
        _verificar_zip(caminho)
    elif sufixo == ".pdf":
        _verificar_pdf(caminho)


def _verificar_zip(caminho: Path) -> None:
    try:
        with zipfile.ZipFile(caminho) as pacote:
            entradas = pacote.infolist()
    except (zipfile.BadZipFile, OSError):
        return
    if len(entradas) > MAX_ENTRADAS_ZIP:
        raise ArquivoForaDosLimites(
            f"planilha com {len(entradas)} entradas internas; o limite é {MAX_ENTRADAS_ZIP}"
        )
    descompactado = sum(entrada.file_size for entrada in entradas)
    if descompactado > MAX_DESCOMPACTADO_MB * 1024 * 1024:
        raise ArquivoForaDosLimites(
            f"planilha com {descompactado // (1024 * 1024)} MB descompactada; "
            f"o limite é {MAX_DESCOMPACTADO_MB} MB"
        )


def _verificar_pdf(caminho: Path) -> None:
    # Conta percorrendo a árvore de páginas (sem ler o conteúdo delas) e para no
    # limite: não confia no /Count declarado, que um PDF forjado pode falsear.
    try:
        with caminho.open("rb") as arquivo:
            for paginas, _ in enumerate(PDFPage.get_pages(arquivo), start=1):
                if paginas > MAX_PAGINAS_PDF:
                    raise ArquivoForaDosLimites(
                        f"PDF com mais de {MAX_PAGINAS_PDF} páginas; o limite é {MAX_PAGINAS_PDF}"
                    )
    except ArquivoForaDosLimites:
        raise
    except Exception:  # PDF malformado: a extração dá o motivo certo
        return
