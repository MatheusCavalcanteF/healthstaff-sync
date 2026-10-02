package br.com.healthstaff.backend.colaborador;

import java.time.LocalDate;

/**
 * Colaborador para listagem. {@code GET /api/v1/colaboradores}.
 *
 * @param vinculo         OFICIAL (tem matrícula do Two) ou PROVISORIO (só na escala)
 * @param categoria       deduzida do cargo (define a cota de FG); nula quando o cargo é desconhecido
 *                        (quem só aparece no Two: o relatório não traz cargo)
 * @param saldoBancoHoras saldo mais recente do Two; nulo para quem não está no Two
 */
public record ColaboradorResposta(
		long id,
		String matricula,
		String nome,
		Vinculo vinculo,
		String cargo,
		CategoriaProfissional categoria,
		String jornada,
		String registroConselho,
		Saldo saldoBancoHoras) {

	public enum Vinculo {
		/** Matrícula confirmada pelo sistema de ponto (Two). */
		OFICIAL,
		/** Está na escala, mas não no Two (recém-contratado, RPA, coordenação...). */
		PROVISORIO
	}

	public record Saldo(int minutos, LocalDate dataReferencia) {
	}

	static ColaboradorResposta de(Colaborador c, Saldo saldo) {
		var categoria = c.getCargo() == null ? null : CategoriaProfissional.de(c.getCargo());
		return new ColaboradorResposta(c.getId(), c.getMatricula(), c.getNome(),
				c.isProvisorio() ? Vinculo.PROVISORIO : Vinculo.OFICIAL, c.getCargo(), categoria, c.getJornada(),
				c.getRegistroConselho(), saldo);
	}
}
