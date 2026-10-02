package br.com.healthstaff.backend.regras;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.Colaborador;
import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.regras.Analise.Gatilho;
import br.com.healthstaff.backend.regras.TipoInconformidade.Gravidade;

/** Leitura da análise vigente de uma competência, com nomes para exibição. */
@Service
public class AnaliseConsulta {

	public record PessoaResumo(long id, String matricula, String nome) {

		static PessoaResumo de(Colaborador c) {
			return new PessoaResumo(c.getId(), c.getMatricula(), c.getNome());
		}
	}

	public record ItemInconformidade(long id, TipoInconformidade tipo, Gravidade gravidade, LocalDate data,
			PessoaResumo colaborador, Long plantaoId, Long plantaoRelacionadoId, Integer valorMinutos,
			Integer limiteMinutos, String descricao) {
	}

	/** {@code minutosCumpridos} = {@code minutosEscalados} (plantão) + {@code minutosCreditoFg}. */
	public record ItemConsolidacao(PessoaResumo colaborador, String jornada, String categoria, int minutosEscalados,
			int folgasOrganizacionais, int folgasOrganizacionaisCreditadas, int minutosCreditoFg, int minutosCumpridos,
			Integer minutosEsperados, Integer diferencaMinutos, Integer saldoMinutos, LocalDate saldoDataReferencia,
			Integer saldoProjetadoMinutos, int plantoes, int diasAusencia, int folgasBancoHoras) {
	}

	public record DetalheAnalise(long id, YearMonth competencia, Instant executadaEm, Gatilho gatilho,
			int colaboradoresAnalisados, int totalInconformidades, List<ItemInconformidade> inconformidades,
			List<ItemConsolidacao> consolidacao) {
	}

	private final AnaliseRepository analises;
	private final InconformidadeRepository inconformidades;
	private final ConsolidacaoHorasRepository consolidacoes;
	private final ColaboradorRepository colaboradores;

	public AnaliseConsulta(AnaliseRepository analises, InconformidadeRepository inconformidades,
			ConsolidacaoHorasRepository consolidacoes, ColaboradorRepository colaboradores) {
		this.analises = analises;
		this.inconformidades = inconformidades;
		this.consolidacoes = consolidacoes;
		this.colaboradores = colaboradores;
	}

	@Transactional(readOnly = true)
	public DetalheAnalise vigente(YearMonth competencia) {
		var analise = analises.findByCompetenciaAndVigenteTrue(competencia.atDay(1))
				.orElseThrow(() -> new AnaliseNaoEncontradaException(competencia));
		var alertas = inconformidades.findByAnaliseIdOrderByDataAscColaboradorIdAscIdAsc(analise.getId());
		var horas = consolidacoes.findByAnaliseIdOrderByColaboradorId(analise.getId());

		Map<Long, PessoaResumo> pessoas = colaboradores.findAllById(Stream.concat(
				alertas.stream().map(Inconformidade::getColaboradorId),
				horas.stream().map(ConsolidacaoHoras::getColaboradorId)).distinct().toList())
				.stream().map(PessoaResumo::de)
				.collect(Collectors.toMap(PessoaResumo::id, Function.identity()));

		return new DetalheAnalise(analise.getId(), analise.getCompetencia(), analise.getExecutadaEm(),
				analise.getGatilho(), analise.getColaboradoresAnalisados(), analise.getTotalInconformidades(),
				alertas.stream().map(i -> new ItemInconformidade(i.getId(), i.getTipo(), i.getGravidade(), i.getData(),
						pessoas.get(i.getColaboradorId()), i.getPlantaoId(), i.getPlantaoRelacionadoId(),
						i.getValorMinutos(), i.getLimiteMinutos(), i.getDescricao())).toList(),
				horas.stream().map(c -> new ItemConsolidacao(pessoas.get(c.getColaboradorId()), c.getJornada(),
						c.getCategoria(), c.getMinutosEscalados(), c.getFolgasOrganizacionais(),
						c.getFolgasOrganizacionaisCreditadas(), c.getMinutosCreditoFg(), c.getMinutosCumpridos(),
						c.getMinutosEsperados(), c.getDiferencaMinutos(), c.getSaldoMinutos(),
						c.getSaldoDataReferencia(), c.getSaldoProjetadoMinutos(), c.getPlantoes(), c.getDiasAusencia(),
						c.getFolgasBancoHoras())).toList());
	}
}
