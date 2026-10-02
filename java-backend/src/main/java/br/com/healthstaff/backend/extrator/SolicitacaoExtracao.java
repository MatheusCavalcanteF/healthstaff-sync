package br.com.healthstaff.backend.extrator;

import java.time.LocalDate;

/**
 * Pedido de extração. Enviar escala e Banco de Horas juntos permite ao
 * extrator usar o cadastro do Two para descobrir a matrícula de quem está
 * na escala.
 *
 * @param escala              escala em grade mensal (PDF ou Excel), opcional
 * @param bancoHoras          relatório de Banco de Horas do Two (Excel ou CSV), opcional
 * @param dataReferenciaPonto data a que os saldos se referem; nula = data da importação
 */
public record SolicitacaoExtracao(
		ArquivoParaExtracao escala,
		ArquivoParaExtracao bancoHoras,
		LocalDate dataReferenciaPonto) {

	public SolicitacaoExtracao {
		if (escala == null && bancoHoras == null) {
			throw new IllegalArgumentException("informe ao menos a escala ou o banco de horas");
		}
	}

	public static SolicitacaoExtracao somenteEscala(ArquivoParaExtracao escala) {
		return new SolicitacaoExtracao(escala, null, null);
	}

	public static SolicitacaoExtracao somenteBancoHoras(ArquivoParaExtracao bancoHoras, LocalDate dataReferencia) {
		return new SolicitacaoExtracao(null, bancoHoras, dataReferencia);
	}
}
