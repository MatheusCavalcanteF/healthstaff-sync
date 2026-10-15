"""Microsserviço interno de extração (chamado pelo java-backend).

Stateless: recebe os arquivos, extrai e devolve os lotes em JSON. Nada é
persistido; os uploads vivem só num diretório temporário durante a
requisição (contêm dados pessoais).

Endpoints:
- ``GET  /health``    verificação de vida (sem autenticação).
- ``GET  /schema``    JSON Schema da resposta de ``/extracoes``.
- ``POST /extracoes`` multipart com ``escala`` e/ou ``banco_horas`` e,
  opcionalmente, ``data_referencia_ponto`` (AAAA-MM-DD).

Quando os dois arquivos vêm juntos, o Banco de Horas é extraído primeiro e
serve de cadastro para inferir a matrícula de quem está na escala.

Configuração por variáveis de ambiente:
- ``EXTRACTOR_API_KEY``: se definida, exige o cabeçalho ``X-API-Key``.
- ``EXTRACTOR_MAX_UPLOAD_MB``: tamanho máximo por arquivo (padrão 20).
"""

import logging
import os
import re
import secrets
import tempfile
from datetime import date
from pathlib import Path
from typing import Annotated, BinaryIO

from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import TypeAdapter, ValidationError

from extractors import ErroExtracao, ExtratorNaoEncontrado, criar_registry_padrao
from models import (
    CodigoInconsistencia,
    ErroApi,
    Inconsistencia,
    ItemCadastro,
    LoteImportacao,
    ResultadoExtracao,
    Severidade,
    TipoDocumento,
)
from utils.limites import ArquivoForaDosLimites
from utils.matcher import CadastroColaborador, ColaboradorMatcher

from .schema import gerar_schema

logger = logging.getLogger(__name__)

VERSAO_API = "1.0.0"
TAMANHO_MAXIMO_PADRAO_MB = 20
TIPOS_ESCALA = {TipoDocumento.ESCALA_PDF, TipoDocumento.ESCALA_EXCEL}
TIPOS_BANCO_HORAS = {TipoDocumento.PONTO_EXCEL, TipoDocumento.PONTO_CSV}
_CARACTERES_INVALIDOS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_BLOCO = 1024 * 1024
_LISTA_CADASTRO = TypeAdapter(list[ItemCadastro])


class ErroRequisicao(Exception):
    def __init__(
        self,
        status: int,
        codigo: str,
        mensagem: str,
        campo: str | None = None,
        arquivo: str | None = None,
    ) -> None:
        super().__init__(mensagem)
        self.status = status
        self.erro = ErroApi(codigo=codigo, mensagem=mensagem, campo=campo, arquivo=arquivo)


def _resposta_erro(status: int, erro: ErroApi) -> JSONResponse:
    return JSONResponse(status_code=status, content=erro.model_dump(mode="json"))


def _nome_seguro(nome: str | None) -> str:
    """Só o nome do arquivo (sem diretórios), sem caracteres inválidos no Windows."""
    base = Path((nome or "").replace("\\", "/")).name
    return _CARACTERES_INVALIDOS.sub("_", base).strip(" .") or "arquivo"


def _salvar(origem: BinaryIO, destino: Path, limite: int, campo: str, nome: str) -> None:
    lidos = 0
    with destino.open("wb") as saida:
        while bloco := origem.read(_BLOCO):
            lidos += len(bloco)
            if lidos > limite:
                raise ErroRequisicao(
                    413,
                    "ARQUIVO_MUITO_GRANDE",
                    f"arquivo excede o limite de {limite // _BLOCO} MB",
                    campo,
                    nome,
                )
            saida.write(bloco)
    if lidos == 0:
        raise ErroRequisicao(422, "ARQUIVO_VAZIO", "arquivo vazio", campo, nome)


def create_app(
    api_key: str | None = None,
    tamanho_maximo_mb: int | None = None,
) -> FastAPI:
    api_key = api_key if api_key is not None else os.environ.get("EXTRACTOR_API_KEY") or None
    if tamanho_maximo_mb is None:
        tamanho_maximo_mb = int(os.environ.get("EXTRACTOR_MAX_UPLOAD_MB", TAMANHO_MAXIMO_PADRAO_MB))
    limite_bytes = tamanho_maximo_mb * _BLOCO
    if not api_key:
        logger.warning(
            "EXTRACTOR_API_KEY não definida: autenticação desativada (use apenas em desenvolvimento local)"
        )

    app = FastAPI(
        title="HealthStaff Sync - Extractor",
        version=VERSAO_API,
        description="Extração de escalas (PDF/Excel) e Banco de Horas do Two (Excel/CSV).",
    )

    erros_documentados = {
        status: {"model": ErroApi} for status in (401, 413, 422)
    }

    @app.exception_handler(ErroRequisicao)
    async def _tratar_erro_requisicao(_: Request, erro: ErroRequisicao) -> JSONResponse:
        return _resposta_erro(erro.status, erro.erro)

    @app.exception_handler(RequestValidationError)
    async def _tratar_validacao(_: Request, erro: RequestValidationError) -> JSONResponse:
        primeiro = erro.errors()[0] if erro.errors() else {}
        campo = str(primeiro.get("loc", ["", ""])[-1]) or None
        return _resposta_erro(
            422,
            ErroApi(
                codigo="REQUISICAO_INVALIDA",
                mensagem=primeiro.get("msg", "requisição inválida"),
                campo=campo,
            ),
        )

    def autenticar(x_api_key: Annotated[str | None, Header()] = None) -> None:
        if api_key and not (x_api_key and secrets.compare_digest(x_api_key, api_key)):
            raise ErroRequisicao(401, "NAO_AUTORIZADO", "cabeçalho X-API-Key ausente ou inválido")

    @app.get("/health", tags=["infra"])
    def health() -> dict[str, str]:
        return {"status": "ok", "versao": VERSAO_API}

    @app.get(
        "/schema",
        tags=["contrato"],
        responses={status: {"model": ErroApi} for status in (401, 422)},
        dependencies=[Depends(autenticar)],
    )
    def schema() -> dict:
        """JSON Schema (draft 2020-12) da resposta de ``POST /extracoes``."""
        return gerar_schema()

    @app.post(
        "/extracoes",
        tags=["extracao"],
        response_model=ResultadoExtracao,
        responses=erros_documentados,
        dependencies=[Depends(autenticar)],
    )
    def extrair(
        escala: Annotated[UploadFile | None, File(description="Escala em PDF ou Excel.")] = None,
        banco_horas: Annotated[
            UploadFile | None, File(description="Banco de Horas do Two em Excel ou CSV.")
        ] = None,
        data_referencia_ponto: Annotated[
            date | None,
            Form(description="Data dos saldos (AAAA-MM-DD). Padrão: data da importação."),
        ] = None,
        cadastro: Annotated[
            str | None,
            Form(
                description=(
                    'JSON com os colaboradores já conhecidos: [{"matricula": "...", "nome": "..."}]. '
                    "Permite inferir matrículas da escala sem enviar o banco de horas junto; "
                    "se ambos vierem, o banco de horas prevalece."
                )
            ),
        ] = None,
    ) -> ResultadoExtracao:
        """Extrai a escala e/ou o Banco de Horas.

        Erros de leitura de células, siglas ou nomes não reconhecidos não
        derrubam a requisição: viram ``inconsistencias`` dentro do lote.
        Só falham arquivos que não podem ser lidos como um todo.
        """
        if escala is None and banco_horas is None:
            raise ErroRequisicao(
                422, "ARQUIVO_AUSENTE", "envie ao menos um arquivo: 'escala' ou 'banco_horas'"
            )
        conhecidos = _ler_cadastro(cadastro)  # valida antes de processar arquivos

        referencia = data_referencia_ponto or date.today()
        resultado = ResultadoExtracao()
        with tempfile.TemporaryDirectory(prefix="extractor-") as tmp:
            if banco_horas is not None:
                resultado.banco_horas = _extrair_arquivo(
                    banco_horas, "banco_horas", TIPOS_BANCO_HORAS, Path(tmp), None, referencia
                )
                if data_referencia_ponto is None:
                    resultado.banco_horas.inconsistencias.append(
                        Inconsistencia(
                            codigo=CodigoInconsistencia.OUTRO,
                            severidade=Severidade.INFO,
                            mensagem=(
                                "data_referencia_ponto não informada; saldos referidos "
                                f"à data da importação ({referencia:%d/%m/%Y})"
                            ),
                        )
                    )

            matcher = _montar_matcher(conhecidos, resultado.banco_horas)
            if escala is not None:
                resultado.escala = _extrair_arquivo(
                    escala, "escala", TIPOS_ESCALA, Path(tmp), matcher, referencia
                )
        return resultado

    def _ler_cadastro(cadastro: str | None) -> list[ItemCadastro]:
        if cadastro is None or not cadastro.strip():
            return []
        try:
            return _LISTA_CADASTRO.validate_json(cadastro)
        except ValidationError as erro:
            raise ErroRequisicao(
                422,
                "CADASTRO_INVALIDO",
                "cadastro deve ser uma lista JSON de {matricula, nome}: "
                + erro.errors()[0]["msg"],
                "cadastro",
            ) from None

    def _montar_matcher(
        cadastro: list[ItemCadastro], banco_horas: LoteImportacao | None
    ) -> ColaboradorMatcher | None:
        """Cadastro do java-backend + Banco de Horas enviado (este prevalece)."""
        pessoas = {item.matricula: CadastroColaborador(item.matricula, item.nome) for item in cadastro}
        if banco_horas is not None:
            for setor in banco_horas.setores:
                for colaborador in setor.colaboradores:
                    if colaborador.matricula:
                        pessoas[colaborador.matricula] = CadastroColaborador(
                            colaborador.matricula, colaborador.nome
                        )
        return ColaboradorMatcher(pessoas.values()) if pessoas else None

    def _extrair_arquivo(
        upload: UploadFile,
        campo: str,
        tipos_aceitos: set[TipoDocumento],
        tmp: Path,
        matcher: ColaboradorMatcher | None,
        referencia: date,
    ) -> LoteImportacao:
        nome = _nome_seguro(upload.filename)
        destino = tmp / campo / nome
        destino.parent.mkdir()
        _salvar(upload.file, destino, limite_bytes, campo, nome)

        registry = criar_registry_padrao(matcher=matcher, data_referencia_ponto=referencia)
        try:
            extrator = registry.selecionar(destino)
        except ArquivoForaDosLimites as erro:
            raise ErroRequisicao(413, "ARQUIVO_MUITO_GRANDE", str(erro), campo, nome) from None
        except ExtratorNaoEncontrado:
            raise ErroRequisicao(
                422,
                "FORMATO_NAO_RECONHECIDO",
                "layout ou extensão não reconhecidos "
                "(escala: PDF/Excel em grade mensal; banco_horas: Excel/CSV do Two)",
                campo,
                nome,
            ) from None

        tipo = extrator.tipo_documento_de(destino)
        if tipo not in tipos_aceitos:
            raise ErroRequisicao(
                422,
                "TIPO_INCOMPATIVEL",
                f"o arquivo foi reconhecido como {tipo.value}, não como {campo}",
                campo,
                nome,
            )

        try:
            lote = extrator.extrair(destino)
        except (ErroExtracao, ValidationError) as erro:
            raise ErroRequisicao(422, "EXTRACAO_FALHOU", str(erro)[:500], campo, nome) from None

        logger.info(
            "extração %s concluída: extrator=%s colaboradores=%d inconsistencias=%d",
            campo,
            extrator.nome,
            lote.total_colaboradores,
            len(lote.inconsistencias),
        )
        return lote

    return app
