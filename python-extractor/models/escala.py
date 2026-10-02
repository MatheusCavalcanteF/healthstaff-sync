"""Schemas Pydantic do contrato JSON enviado ao java-backend.

Hierarquia: LoteImportacao -> Setor -> Colaborador -> Plantao.
Os campos usam snake_case no Python e são serializados em camelCase
para o backend (Jackson). Datas/horas são locais (sem fuso), mapeando
para LocalDate/LocalDateTime no Java.
"""

import re
import unicodedata
from datetime import date, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID, uuid4

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    computed_field,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from .enums import (
    CodigoInconsistencia,
    Severidade,
    TipoDocumento,
    TipoMarcacao,
    Turno,
)

VERSAO_CONTRATO = "1.0"
DURACAO_MAXIMA_PLANTAO = timedelta(hours=24)

TextoNaoVazio = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


def normalizar_nome(nome: str) -> str:
    """Remove acentos, colapsa espaços e coloca em maiúsculas (para matching)."""
    sem_acento = unicodedata.normalize("NFKD", nome).encode("ascii", "ignore").decode()
    return re.sub(r"\s+", " ", sem_acento).strip().upper()


class ContratoBase(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_name=True,
        validate_by_alias=True,
        serialize_by_alias=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    @model_validator(mode="before")
    @classmethod
    def _ignorar_campos_calculados(cls, dados: object) -> object:
        """Descarta campos computados presentes no JSON (são sempre recalculados)."""
        if not isinstance(dados, dict) or not cls.__pydantic_computed_fields__:
            return dados
        calculados = set(cls.__pydantic_computed_fields__)
        calculados |= {to_camel(nome) for nome in calculados}
        return {k: v for k, v in dados.items() if k not in calculados}


class Origem(ContratoBase):
    """Metadados do arquivo de onde os dados foram extraídos."""

    tipo_documento: TipoDocumento
    nome_arquivo: TextoNaoVazio
    hash_sha256: Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
    tamanho_bytes: int = Field(ge=0)
    total_paginas: int | None = Field(default=None, ge=1)
    extrator: TextoNaoVazio
    versao_extrator: TextoNaoVazio
    processado_em: datetime = Field(default_factory=datetime.now)


class Plantao(ContratoBase):
    """Uma marcação da escala para um colaborador em um dia."""

    data: date
    tipo: TipoMarcacao
    codigo_original: TextoNaoVazio = Field(
        description="Texto bruto da célula no PDF (ex.: 'M', 'SN', 'P12', 'F')."
    )
    turno: Turno | None = None
    inicio: datetime | None = None
    fim: datetime | None = None
    pagina: int | None = Field(default=None, ge=1)
    observacao: str | None = Field(
        default=None, description="Ex.: folga para compensação de banco de horas."
    )

    @model_validator(mode="after")
    def _validar_horarios(self) -> "Plantao":
        if self.tipo is TipoMarcacao.PLANTAO:
            if self.inicio is None or self.fim is None:
                raise ValueError("plantão exige 'inicio' e 'fim'")
            if self.inicio.date() != self.data:
                raise ValueError("'inicio' deve ocorrer na mesma data do plantão")
            if self.fim <= self.inicio:
                raise ValueError("'fim' deve ser posterior a 'inicio'")
            if self.fim - self.inicio > DURACAO_MAXIMA_PLANTAO:
                raise ValueError("plantão com duração superior a 24h")
        elif self.inicio is not None or self.fim is not None:
            raise ValueError(f"marcação do tipo {self.tipo} não deve ter horários")
        return self

    @computed_field
    @property
    def duracao_horas(self) -> float:
        if self.inicio is None or self.fim is None:
            return 0.0
        return round((self.fim - self.inicio).total_seconds() / 3600, 2)


class RegistroPonto(ContratoBase):
    """Par entrada/saída efetivamente registrado no relógio de ponto.

    ``data`` é a data de referência da jornada; ``entrada``/``saida`` podem
    cair no dia seguinte (plantão noturno). Um dos dois pode faltar quando a
    marcação está incompleta (batida esquecida).
    """

    data: date
    entrada: datetime | None = None
    saida: datetime | None = None

    @model_validator(mode="after")
    def _validar_marcacoes(self) -> "RegistroPonto":
        if self.entrada is None and self.saida is None:
            raise ValueError("registro de ponto exige 'entrada' ou 'saida'")
        for marcacao in (self.entrada, self.saida):
            if marcacao is not None and not 0 <= (marcacao.date() - self.data).days <= 1:
                raise ValueError("marcação deve ocorrer na data do registro ou no dia seguinte")
        if self.entrada is not None and self.saida is not None:
            if self.saida <= self.entrada:
                raise ValueError("'saida' deve ser posterior a 'entrada'")
            if self.saida - self.entrada > DURACAO_MAXIMA_PLANTAO:
                raise ValueError("intervalo entre entrada e saída superior a 24h")
        return self

    @computed_field
    @property
    def duracao_horas(self) -> float:
        if self.entrada is None or self.saida is None:
            return 0.0
        return round((self.saida - self.entrada).total_seconds() / 3600, 2)


class SaldoBancoHoras(ContratoBase):
    """Saldo de banco de horas informado pelo sistema de ponto."""

    minutos: int = Field(description="Positivo = crédito, negativo = débito.")
    data_referencia: date | None = Field(
        default=None, description="Data a que o saldo se refere."
    )
    inicio_apuracao: date | None = Field(
        default=None, description="Início da apuração do banco de horas no sistema de ponto."
    )

    @computed_field
    @property
    def horas(self) -> float:
        return round(self.minutos / 60, 2)


class Colaborador(ContratoBase):
    matricula: str | None = Field(
        default=None,
        description="Pode faltar no PDF; o backend tenta casar pelo nome normalizado.",
    )
    nome: TextoNaoVazio
    cargo: str | None = None
    registro_conselho: str | None = Field(
        default=None, description="Registro no conselho profissional (COREN, CRM...)."
    )
    jornada: str | None = Field(default=None, description="Ex.: '12x36', '40H'.")
    folguista: bool = Field(
        default=False,
        description="Profissional sem escala fixa (folguista/ferista), cobrindo este setor.",
    )
    plantoes: list[Plantao] = Field(default_factory=list)
    registros_ponto: list[RegistroPonto] = Field(default_factory=list)
    saldo_banco_horas: SaldoBancoHoras | None = None

    @field_validator("matricula", "cargo", "registro_conselho", "jornada", mode="before")
    @classmethod
    def _vazio_para_none(cls, valor: object) -> object:
        if isinstance(valor, str) and not valor.strip():
            return None
        return valor

    @field_validator("nome")
    @classmethod
    def _colapsar_espacos(cls, valor: str) -> str:
        return re.sub(r"\s+", " ", valor)

    @computed_field
    @property
    def nome_normalizado(self) -> str:
        return normalizar_nome(self.nome)


class Setor(ContratoBase):
    codigo: str | None = None
    nome: TextoNaoVazio
    colaboradores: list[Colaborador] = Field(default_factory=list)


class Inconsistencia(ContratoBase):
    """Problema encontrado na extração, para revisão humana no RH."""

    codigo: CodigoInconsistencia
    severidade: Severidade
    mensagem: TextoNaoVazio
    pagina: int | None = Field(default=None, ge=1)
    aba: str | None = Field(default=None, description="Aba da planilha (Excel).")
    linha: int | None = Field(default=None, ge=1, description="Linha da planilha (Excel).")
    setor: str | None = None
    matricula: str | None = None
    nome_colaborador: str | None = None
    data: date | None = None
    valor_bruto: str | None = Field(
        default=None, description="Trecho original do documento que gerou o problema."
    )


class LoteImportacao(ContratoBase):
    """Payload raiz enviado ao java-backend para um arquivo processado."""

    id_lote: UUID = Field(default_factory=uuid4)
    versao_contrato: Literal["1.0"] = VERSAO_CONTRATO
    gerado_em: datetime = Field(default_factory=datetime.now)
    origem: Origem
    periodo_inicio: date
    periodo_fim: date
    setores: list[Setor] = Field(default_factory=list)
    inconsistencias: list[Inconsistencia] = Field(default_factory=list)

    @model_validator(mode="after")
    def _validar_periodo(self) -> "LoteImportacao":
        if self.periodo_fim < self.periodo_inicio:
            raise ValueError("'periodo_fim' anterior a 'periodo_inicio'")
        for setor in self.setores:
            for colaborador in setor.colaboradores:
                for plantao in colaborador.plantoes:
                    if not self.periodo_inicio <= plantao.data <= self.periodo_fim:
                        raise ValueError(
                            f"plantão de {colaborador.nome} em {plantao.data} "
                            f"fora do período do lote"
                        )
                for registro in colaborador.registros_ponto:
                    if not self.periodo_inicio <= registro.data <= self.periodo_fim:
                        raise ValueError(
                            f"registro de ponto de {colaborador.nome} em {registro.data} "
                            f"fora do período do lote"
                        )
        return self

    @computed_field
    @property
    def total_colaboradores(self) -> int:
        return sum(len(s.colaboradores) for s in self.setores)

    @computed_field
    @property
    def total_plantoes(self) -> int:
        return sum(
            1
            for s in self.setores
            for c in s.colaboradores
            for p in c.plantoes
            if p.tipo is TipoMarcacao.PLANTAO
        )

    @computed_field
    @property
    def possui_erros(self) -> bool:
        return any(i.severidade is Severidade.ERRO for i in self.inconsistencias)

    def to_json(self, **kwargs) -> str:
        return self.model_dump_json(**kwargs)


class ResultadoExtracao(ContratoBase):
    """Resposta de ``POST /extracoes``: um lote por arquivo enviado."""

    escala: LoteImportacao | None = Field(
        default=None, description="Lote da escala (grade mensal), se enviada."
    )
    banco_horas: LoteImportacao | None = Field(
        default=None, description="Lote do relatório de Banco de Horas do Two, se enviado."
    )


class ErroApi(ContratoBase):
    """Corpo de toda resposta de erro da API."""

    codigo: str = Field(description="Ex.: FORMATO_NAO_RECONHECIDO, TIPO_INCOMPATIVEL.")
    mensagem: str
    campo: str | None = Field(default=None, description="Campo do formulário com problema.")
    arquivo: str | None = None


class ItemCadastro(ContratoBase):
    """Colaborador já conhecido pelo java-backend, enviado em ``cadastro`` para
    o matcher associar nomes da escala mesmo sem o relatório do Two junto."""

    matricula: TextoNaoVazio
    nome: TextoNaoVazio
