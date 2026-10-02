package br.com.healthstaff.backend.regras;

/** Regras de negócio violadas, com a gravidade de cada uma. */
public enum TipoInconformidade {

	/** Descanso entre duas jornadas abaixo do mínimo legal (CLT art. 66: 11h). */
	INTERJORNADA(Gravidade.ALTA),

	/** Dois plantões do mesmo colaborador ao mesmo tempo (no mesmo ou em outro setor). */
	SOBREPOSICAO_PLANTOES(Gravidade.CRITICA),

	/** Plantões emendados formando jornada acima do máximo (ex.: PD + PN = 24h). */
	JORNADA_EXCESSIVA(Gravidade.ALTA),

	/** Saldo de banco de horas alto e escala acima da carga do mês: acumula ainda mais. */
	SALDO_POSITIVO_ESCALA_ACIMA(Gravidade.MEDIA),

	/** Saldo negativo e escala abaixo da carga do mês: a dívida aumenta. */
	SALDO_NEGATIVO_ESCALA_ABAIXO(Gravidade.MEDIA),

	/** Folga de compensação (BH) sem saldo positivo para compensar. */
	FOLGA_BH_SEM_SALDO(Gravidade.ALTA);

	private final Gravidade gravidade;

	TipoInconformidade(Gravidade gravidade) {
		this.gravidade = gravidade;
	}

	public Gravidade gravidade() {
		return gravidade;
	}

	public enum Gravidade {
		CRITICA, ALTA, MEDIA
	}
}
