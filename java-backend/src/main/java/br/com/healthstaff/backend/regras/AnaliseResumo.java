package br.com.healthstaff.backend.regras;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import br.com.healthstaff.backend.regras.Analise.Gatilho;
import br.com.healthstaff.backend.regras.TipoInconformidade.Gravidade;

/**
 * Resumo da análise vigente de uma competência (painel do mês).
 * {@code GET /api/v1/analises/{AAAA-MM}}.
 */
public record AnaliseResumo(
		long id,
		YearMonth competencia,
		Instant executadaEm,
		Gatilho gatilho,
		UUID importacaoId,
		int colaboradoresAnalisados,
		Alertas inconformidades,
		Horas horas) {

	/**
	 * @param porGravidade todas as gravidades, inclusive as com zero
	 * @param porTipo      todos os tipos, inclusive os com zero
	 */
	public record Alertas(
			int total,
			int colaboradoresAfetados,
			Map<Gravidade, Long> porGravidade,
			Map<TipoInconformidade, Long> porTipo) {
	}

	/**
	 * Totais da consolidação. "Cumpridos" = plantão + crédito de FG; as
	 * comparações com a carga só consideram quem tem carga definida.
	 *
	 * @param saldoMinutos          soma dos saldos do Two de quem está na escala
	 * @param saldoProjetadoMinutos soma dos saldos projetados para o fim do mês
	 */
	public record Horas(
			long minutosEscalados,
			long minutosCreditoFg,
			long minutosCumpridos,
			long minutosEsperados,
			long diferencaMinutos,
			int colaboradoresAbaixoDaCarga,
			int colaboradoresNaCarga,
			int colaboradoresAcimaDaCarga,
			int colaboradoresSemCargaDefinida,
			int colaboradoresComSaldo,
			int colaboradoresSaldoAlto,
			int colaboradoresSaldoBaixo,
			long saldoMinutos,
			long saldoProjetadoMinutos) {
	}
}
