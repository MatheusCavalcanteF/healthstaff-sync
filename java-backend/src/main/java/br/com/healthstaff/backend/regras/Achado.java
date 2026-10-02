package br.com.healthstaff.backend.regras;

import java.time.LocalDate;

/**
 * Inconformidade encontrada por uma regra, antes de ser gravada.
 *
 * @param data                 dia a que o alerta se refere (ex.: dia do plantão que começou cedo demais)
 * @param plantaoId            plantão que caracteriza o problema, se houver
 * @param plantaoRelacionadoId o outro plantão envolvido (anterior/sobreposto), se houver
 * @param valorMinutos         medida encontrada (descanso, duração, saldo...)
 * @param limiteMinutos        limite da regra para comparação
 */
public record Achado(
		TipoInconformidade tipo,
		long colaboradorId,
		LocalDate data,
		Long plantaoId,
		Long plantaoRelacionadoId,
		Integer valorMinutos,
		Integer limiteMinutos,
		String descricao) {
}
