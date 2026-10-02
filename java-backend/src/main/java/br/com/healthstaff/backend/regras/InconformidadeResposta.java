package br.com.healthstaff.backend.regras;

import java.time.LocalDate;
import java.time.LocalDateTime;

import br.com.healthstaff.backend.colaborador.Colaborador;
import br.com.healthstaff.backend.escala.Plantao;
import br.com.healthstaff.backend.regras.TipoInconformidade.Gravidade;

/**
 * Alerta da análise, com o colaborador e os plantões envolvidos já resolvidos
 * para exibição. {@code GET /api/v1/analises/{AAAA-MM}/inconformidades}.
 *
 * @param plantao            plantão que caracteriza o problema (nulo nos alertas de saldo)
 * @param plantaoRelacionado o outro plantão (anterior ou sobreposto), se houver
 */
public record InconformidadeResposta(
		long id,
		TipoInconformidade tipo,
		Gravidade gravidade,
		LocalDate data,
		Pessoa colaborador,
		PlantaoResumo plantao,
		PlantaoResumo plantaoRelacionado,
		Integer valorMinutos,
		Integer limiteMinutos,
		String descricao) {

	public record Pessoa(long id, String matricula, String nome, boolean provisorio) {

		static Pessoa de(Colaborador colaborador) {
			return new Pessoa(colaborador.getId(), colaborador.getMatricula(), colaborador.getNome(),
					colaborador.isProvisorio());
		}
	}

	public record PlantaoResumo(long id, LocalDate data, LocalDateTime inicio, LocalDateTime fim, String turno,
			String codigoOriginal, String setor) {

		static PlantaoResumo de(Plantao plantao) {
			return new PlantaoResumo(plantao.getId(), plantao.getData(), plantao.getInicio(), plantao.getFim(),
					plantao.getTurno(), plantao.getCodigoOriginal(), plantao.getSetor().getNome());
		}
	}
}
