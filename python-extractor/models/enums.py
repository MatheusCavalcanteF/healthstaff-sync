"""Enumerações do contrato de importação de escalas."""

from enum import StrEnum


class TipoDocumento(StrEnum):
    ESCALA_PDF = "ESCALA_PDF"
    ESCALA_EXCEL = "ESCALA_EXCEL"
    PONTO_EXCEL = "PONTO_EXCEL"
    PONTO_CSV = "PONTO_CSV"


class TipoMarcacao(StrEnum):
    """O que a célula da escala representa para o colaborador naquele dia."""

    PLANTAO = "PLANTAO"
    FOLGA = "FOLGA"
    FERIAS = "FERIAS"
    LICENCA = "LICENCA"
    AFASTAMENTO = "AFASTAMENTO"
    FALTA = "FALTA"
    DESCONHECIDO = "DESCONHECIDO"


class Turno(StrEnum):
    MANHA = "MANHA"
    TARDE = "TARDE"
    NOITE = "NOITE"
    DIURNO_12H = "DIURNO_12H"
    NOTURNO_12H = "NOTURNO_12H"
    PLANTAO_24H = "PLANTAO_24H"
    OUTRO = "OUTRO"


class Severidade(StrEnum):
    INFO = "INFO"
    AVISO = "AVISO"
    ERRO = "ERRO"


class CodigoInconsistencia(StrEnum):
    """Problemas detectados durante a extração (não regras de negócio).

    Validações como interjornada e saldo de banco de horas são
    responsabilidade do java-backend.
    """

    LAYOUT_NAO_RECONHECIDO = "LAYOUT_NAO_RECONHECIDO"
    PERIODO_NAO_IDENTIFICADO = "PERIODO_NAO_IDENTIFICADO"
    SETOR_NAO_IDENTIFICADO = "SETOR_NAO_IDENTIFICADO"
    CELULA_ILEGIVEL = "CELULA_ILEGIVEL"
    CODIGO_TURNO_DESCONHECIDO = "CODIGO_TURNO_DESCONHECIDO"
    DATA_INVALIDA = "DATA_INVALIDA"
    COLABORADOR_SEM_MATRICULA = "COLABORADOR_SEM_MATRICULA"
    COLABORADOR_NAO_RECONHECIDO = "COLABORADOR_NAO_RECONHECIDO"
    COLABORADOR_DUPLICADO = "COLABORADOR_DUPLICADO"
    PLANTAO_DUPLICADO = "PLANTAO_DUPLICADO"
    MARCACAO_INCOMPLETA = "MARCACAO_INCOMPLETA"
    MARCACAO_INVALIDA = "MARCACAO_INVALIDA"
    SALDO_INVALIDO = "SALDO_INVALIDO"
    TOTAL_DIVERGENTE = "TOTAL_DIVERGENTE"
    OUTRO = "OUTRO"
