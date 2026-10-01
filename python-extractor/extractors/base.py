"""Interface comum dos extratores de escala/ponto (padrão Strategy).

Cada formato de arquivo (layout de PDF dos gestores, CSV do sistema de
ponto...) ganha uma implementação concreta de ``BaseExtractor``. O
orquestrador escolhe a estratégia chamando ``pode_processar`` e depois
executa ``extrair``, que devolve sempre um ``LoteImportacao`` no mesmo
formato, independente da origem.

Os padrões da classe base são de PDF (aberto com pdfplumber); extratores de
outros formatos sobrescrevem ``tipo_documento`` (ou ``tipo_documento_de``),
``extensoes``, ``abrir`` e ``contar_paginas``.
"""

import hashlib
from abc import ABC, abstractmethod
from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, ClassVar

import pdfplumber

from models import (
    CodigoInconsistencia,
    Inconsistencia,
    LoteImportacao,
    Origem,
    Setor,
    Severidade,
    TipoDocumento,
)


class ErroExtracao(Exception):
    """Falha que impede gerar o lote (ex.: período ilegível, layout errado)."""


@dataclass
class ContextoExtracao:
    """Estado de uma execução de extração, passado às etapas do extrator.

    Mantém o extrator sem estado próprio, permitindo reutilizar a mesma
    instância para vários arquivos.
    """

    caminho: Path
    inconsistencias: list[Inconsistencia] = field(default_factory=list)

    def registrar(
        self,
        codigo: CodigoInconsistencia,
        mensagem: str,
        severidade: Severidade = Severidade.AVISO,
        **detalhes,
    ) -> None:
        self.inconsistencias.append(
            Inconsistencia(
                codigo=codigo, severidade=severidade, mensagem=mensagem, **detalhes
            )
        )


class BaseExtractor(ABC):
    """Estratégia de extração para um formato específico de arquivo."""

    nome: ClassVar[str]
    versao: ClassVar[str]
    tipo_documento: ClassVar[TipoDocumento] = TipoDocumento.ESCALA_PDF
    extensoes: ClassVar[frozenset[str]] = frozenset({".pdf"})

    def __init_subclass__(cls, **kwargs) -> None:
        super().__init_subclass__(**kwargs)
        if ABC not in cls.__bases__:
            for atributo in ("nome", "versao"):
                if not getattr(cls, atributo, None):
                    raise TypeError(f"{cls.__name__} deve definir '{atributo}'")

    def aceita_extensao(self, caminho: str | Path) -> bool:
        return Path(caminho).suffix.lower() in self.extensoes

    def tipo_documento_de(self, caminho: Path) -> TipoDocumento:
        return self.tipo_documento

    def abrir(self, caminho: Path) -> AbstractContextManager[Any]:
        """Abre o arquivo e devolve o documento entregue às demais etapas."""
        return pdfplumber.open(caminho)

    def contar_paginas(self, documento: Any) -> int | None:
        return len(documento.pages)

    @abstractmethod
    def pode_processar(self, documento: Any) -> bool:
        """Indica se este extrator reconhece o layout do documento.

        Deve ser rápido e sem efeitos colaterais (ex.: checar cabeçalho
        da primeira página).
        """

    @abstractmethod
    def extrair_periodo(self, documento: Any, ctx: ContextoExtracao) -> tuple[date, date]:
        """Retorna (inicio, fim) do período coberto pelo documento.

        Deve lançar ``ErroExtracao`` se o período não puder ser determinado.
        """

    @abstractmethod
    def extrair_setores(self, documento: Any, ctx: ContextoExtracao) -> list[Setor]:
        """Extrai setores, colaboradores e suas marcações do documento.

        Problemas recuperáveis (célula ilegível, código de turno
        desconhecido, colaborador sem matrícula...) devem ser registrados
        via ``ctx.registrar`` em vez de interromper a extração.
        """

    def extrair(self, caminho: str | Path) -> LoteImportacao:
        """Template method: abre o arquivo, executa as etapas e monta o lote."""
        caminho = Path(caminho)
        if not caminho.is_file():
            raise FileNotFoundError(caminho)

        ctx = ContextoExtracao(caminho=caminho)
        with self.abrir(caminho) as documento:
            if not self.pode_processar(documento):
                raise ErroExtracao(
                    f"{self.nome} não reconhece o layout de '{caminho.name}'"
                )
            origem = self._montar_origem(caminho, self.contar_paginas(documento))
            periodo_inicio, periodo_fim = self.extrair_periodo(documento, ctx)
            setores = self.extrair_setores(documento, ctx)

        return LoteImportacao(
            origem=origem,
            periodo_inicio=periodo_inicio,
            periodo_fim=periodo_fim,
            setores=setores,
            inconsistencias=ctx.inconsistencias,
        )

    def _montar_origem(self, caminho: Path, total_paginas: int | None) -> Origem:
        conteudo = caminho.read_bytes()
        return Origem(
            tipo_documento=self.tipo_documento_de(caminho),
            nome_arquivo=caminho.name,
            hash_sha256=hashlib.sha256(conteudo).hexdigest(),
            tamanho_bytes=len(conteudo),
            total_paginas=total_paginas,
            extrator=self.nome,
            versao_extrator=self.versao,
        )
