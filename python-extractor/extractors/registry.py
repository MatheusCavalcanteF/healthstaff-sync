"""Registro dos extratores concretos e seleção da estratégia por arquivo."""

import logging
from collections.abc import Iterator
from datetime import date
from pathlib import Path

from models import LoteImportacao
from utils.matcher import ColaboradorMatcher

from .base import BaseExtractor, ErroExtracao
from .grid import GridExtractor
from .ponto_csv import PontoCsvExtractor
from .ponto_excel import PontoExcelExtractor

logger = logging.getLogger(__name__)


class ExtratorNaoEncontrado(ErroExtracao):
    """Nenhum extrator registrado reconhece o layout do arquivo."""


class ExtractorRegistry:
    """Mantém os extratores disponíveis e escolhe qual usar para cada PDF.

    A seleção segue a ordem de registro: o primeiro extrator cujo
    ``pode_processar`` retornar ``True`` é escolhido. Registre layouts
    específicos antes de extratores genéricos/de fallback.
    """

    def __init__(self, extratores: list[BaseExtractor] | None = None) -> None:
        self._extratores: dict[str, BaseExtractor] = {}
        for extrator in extratores or []:
            self.registrar(extrator)

    def registrar(self, extrator: BaseExtractor) -> BaseExtractor:
        if not isinstance(extrator, BaseExtractor):
            raise TypeError(
                f"esperado BaseExtractor, recebido {type(extrator).__name__}"
            )
        if extrator.nome in self._extratores:
            raise ValueError(f"extrator '{extrator.nome}' já registrado")
        self._extratores[extrator.nome] = extrator
        return extrator

    def remover(self, nome: str) -> None:
        try:
            del self._extratores[nome]
        except KeyError:
            raise KeyError(f"extrator '{nome}' não registrado") from None

    def obter(self, nome: str) -> BaseExtractor:
        try:
            return self._extratores[nome]
        except KeyError:
            raise KeyError(f"extrator '{nome}' não registrado") from None

    @property
    def extratores(self) -> list[BaseExtractor]:
        return list(self._extratores.values())

    def __len__(self) -> int:
        return len(self._extratores)

    def __contains__(self, nome: object) -> bool:
        return nome in self._extratores

    def __iter__(self) -> Iterator[BaseExtractor]:
        return iter(self.extratores)

    def selecionar(self, caminho: str | Path) -> BaseExtractor:
        """Retorna o primeiro extrator que reconhece o layout do arquivo.

        Só são consultados extratores que aceitam a extensão do arquivo.
        Um extrator que lança exceção ao abrir o arquivo ou em
        ``pode_processar`` é tratado como incapaz de processá-lo, para não
        bloquear os demais.
        """
        caminho = Path(caminho)
        if not caminho.is_file():
            raise FileNotFoundError(caminho)

        for extrator in self._extratores.values():
            if not extrator.aceita_extensao(caminho):
                continue
            try:
                with extrator.abrir(caminho) as documento:
                    if extrator.pode_processar(documento):
                        return extrator
            except Exception:
                logger.exception(
                    "falha em %s ao avaliar '%s'", extrator.nome, caminho.name
                )

        raise ExtratorNaoEncontrado(
            f"nenhum extrator reconhece o layout de '{caminho.name}' "
            f"(registrados: {', '.join(self._extratores) or 'nenhum'})"
        )

    def extrair(self, caminho: str | Path) -> LoteImportacao:
        """Seleciona o extrator adequado e executa a extração."""
        return self.selecionar(caminho).extrair(caminho)


def criar_registry_padrao(
    matcher: ColaboradorMatcher | None = None,
    data_referencia_ponto: date | None = None,
) -> ExtractorRegistry:
    """Registry com os extratores de produção.

    - escala (grade mensal): PDF ou Excel;
    - Banco de Horas do Two: Excel ou CSV.

    Para .xlsx a escolha entre escala e ponto é feita pelo layout.
    """
    return ExtractorRegistry(
        [
            GridExtractor(matcher=matcher),
            PontoExcelExtractor(data_referencia=data_referencia_ponto),
            PontoCsvExtractor(data_referencia=data_referencia_ponto),
        ]
    )
