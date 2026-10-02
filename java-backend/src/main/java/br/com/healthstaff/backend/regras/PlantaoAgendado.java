package br.com.healthstaff.backend.regras;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Plantão de trabalho de uma escala vigente, como as regras de jornada o enxergam. */
public record PlantaoAgendado(
		long id,
		long colaboradorId,
		long setorId,
		LocalDate data,
		LocalDateTime inicio,
		LocalDateTime fim) {

	public Duration duracao() {
		return Duration.between(inicio, fim);
	}
}
