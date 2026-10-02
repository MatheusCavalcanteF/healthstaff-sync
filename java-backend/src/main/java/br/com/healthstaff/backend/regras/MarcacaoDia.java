package br.com.healthstaff.backend.regras;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/** Qualquer marcação de escala vigente (plantão, folga, férias...) usada na consolidação. */
public record MarcacaoDia(
		long colaboradorId,
		LocalDate data,
		String tipo,
		String codigoOriginal,
		String observacao,
		LocalDateTime inicio,
		LocalDateTime fim) {

	/** Ausências que reduzem a carga esperada do mês (falta não reduz: vira débito). */
	static final Set<String> AUSENCIAS_JUSTIFICADAS = Set.of("FERIAS", "LICENCA", "AFASTAMENTO");

	boolean ehPlantao() {
		return "PLANTAO".equals(tipo) && inicio != null && fim != null;
	}

	boolean ehAusenciaJustificada() {
		return AUSENCIAS_JUSTIFICADAS.contains(tipo);
	}

	/** Folga para compensar banco de horas (sigla "BH" na escala). */
	boolean ehFolgaBancoHoras() {
		return "FOLGA".equals(tipo)
				&& ("BH".equalsIgnoreCase(codigoOriginal == null ? "" : codigoOriginal.strip())
						|| (observacao != null && observacao.toLowerCase(Locale.ROOT).contains("banco de horas")));
	}

	/** Folga organizacional (sigla "FG", mensal/quinzenal): crédito de jornada, não ausência. */
	boolean ehFolgaOrganizacional() {
		return "FOLGA".equals(tipo) && codigoOriginal != null && "FG".equalsIgnoreCase(codigoOriginal.strip());
	}

	Duration duracao() {
		return ehPlantao() ? Duration.between(inicio, fim) : Duration.ZERO;
	}
}
