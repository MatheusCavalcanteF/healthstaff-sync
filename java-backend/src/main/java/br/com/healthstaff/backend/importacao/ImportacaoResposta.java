package br.com.healthstaff.backend.importacao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de uma importação, devolvido ao frontend.
 *
 * @param duplicada verdadeiro quando o mesmo arquivo já havia sido importado
 *                  (os dados são os da importação original)
 * @param escalas   versões de escala criadas (vazio para o banco de horas)
 * @param saldos    saldos de banco de horas gravados (zero para escalas)
 */
public record ImportacaoResposta(
		UUID id,
		String tipoDocumento,
		String nomeArquivo,
		boolean duplicada,
		LocalDate periodoInicio,
		LocalDate periodoFim,
		Instant importadoEm,
		int totalColaboradores,
		int totalPlantoes,
		boolean possuiErros,
		List<VersaoEscala> escalas,
		long saldos,
		List<ItemInconsistencia> inconsistencias) {

	public record VersaoEscala(String setor, LocalDate competencia, int versao, boolean vigente, long marcacoes) {
	}

	public record ItemInconsistencia(
			String codigo,
			String severidade,
			String mensagem,
			String aba,
			Integer linha,
			Integer pagina,
			String setor,
			String matricula,
			String nomeColaborador,
			LocalDate data,
			String valorBruto) {

		static ItemInconsistencia de(InconsistenciaImportacao origem) {
			return new ItemInconsistencia(origem.getCodigo(), origem.getSeveridade(), origem.getMensagem(),
					origem.getAba(), origem.getLinha(), origem.getPagina(), origem.getSetor(),
					origem.getMatricula(), origem.getNomeColaborador(), origem.getData(), origem.getValorBruto());
		}
	}
}
