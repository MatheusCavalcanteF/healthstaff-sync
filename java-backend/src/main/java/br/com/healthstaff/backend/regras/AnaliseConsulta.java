package br.com.healthstaff.backend.regras;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.escala.PlantaoRepository;
import br.com.healthstaff.backend.regras.InconformidadeResposta.Pessoa;
import br.com.healthstaff.backend.regras.InconformidadeResposta.PlantaoResumo;
import br.com.healthstaff.backend.regras.TipoInconformidade.Gravidade;

/** Leitura da análise vigente de uma competência para o frontend. */
@Service
public class AnaliseConsulta {

	/** Filtros opcionais da lista de inconformidades (nulo = todos). */
	public record Filtros(TipoInconformidade tipo, Gravidade gravidade, Long colaboradorId) {
	}

	private final AnaliseRepository analises;
	private final InconformidadeRepository inconformidades;
	private final ConsolidacaoHorasRepository consolidacoes;
	private final ColaboradorRepository colaboradores;
	private final PlantaoRepository plantoes;
	private final RegrasProperties regras;

	public AnaliseConsulta(AnaliseRepository analises, InconformidadeRepository inconformidades,
			ConsolidacaoHorasRepository consolidacoes, ColaboradorRepository colaboradores, PlantaoRepository plantoes,
			RegrasProperties regras) {
		this.analises = analises;
		this.inconformidades = inconformidades;
		this.consolidacoes = consolidacoes;
		this.colaboradores = colaboradores;
		this.plantoes = plantoes;
		this.regras = regras;
	}

	@Transactional(readOnly = true)
	public AnaliseResumo resumo(YearMonth competencia) {
		var analise = vigente(competencia);
		var alertas = inconformidades.findByAnaliseIdOrderByDataAscColaboradorIdAscIdAsc(analise.getId());

		var porGravidade = new EnumMap<Gravidade, Long>(Gravidade.class);
		for (var gravidade : Gravidade.values()) {
			porGravidade.put(gravidade, 0L);
		}
		var porTipo = new EnumMap<TipoInconformidade, Long>(TipoInconformidade.class);
		for (var tipo : TipoInconformidade.values()) {
			porTipo.put(tipo, 0L);
		}
		alertas.forEach(a -> {
			porGravidade.merge(a.getGravidade(), 1L, Long::sum);
			porTipo.merge(a.getTipo(), 1L, Long::sum);
		});
		int afetados = (int) alertas.stream().map(Inconformidade::getColaboradorId).distinct().count();

		var t = consolidacoes.totais(analise.getId(), (int) regras.saldoAlto().toMinutes(),
				(int) regras.saldoBaixo().toMinutes());
		var horas = new AnaliseResumo.Horas(t.minutosEscalados(), t.minutosCreditoFg(), t.minutosCumpridos(),
				t.minutosEsperados(), t.diferencaMinutos(), (int) t.abaixo(), (int) t.naCarga(), (int) t.acima(),
				(int) t.semCarga(), (int) t.comSaldo(), (int) t.saldoAlto(), (int) t.saldoBaixo(), t.saldoMinutos(),
				t.saldoProjetadoMinutos());

		return new AnaliseResumo(analise.getId(), analise.getCompetencia(), analise.getExecutadaEm(),
				analise.getGatilho(), analise.getImportacaoId(), analise.getColaboradoresAnalisados(),
				new AnaliseResumo.Alertas(alertas.size(), afetados, porGravidade, porTipo), horas);
	}

	/** Mais graves primeiro; depois por data e nome. */
	@Transactional(readOnly = true)
	public List<InconformidadeResposta> inconformidades(YearMonth competencia, Filtros filtros) {
		var analise = vigente(competencia);
		var alertas = inconformidades.findByAnaliseIdOrderByDataAscColaboradorIdAscIdAsc(analise.getId()).stream()
				.filter(a -> filtros.tipo() == null || a.getTipo() == filtros.tipo())
				.filter(a -> filtros.gravidade() == null || a.getGravidade() == filtros.gravidade())
				.filter(a -> filtros.colaboradorId() == null || a.getColaboradorId().equals(filtros.colaboradorId()))
				.toList();

		var pessoas = colaboradores.findAllById(ids(alertas.stream().map(Inconformidade::getColaboradorId))).stream()
				.map(Pessoa::de)
				.collect(Collectors.toMap(Pessoa::id, Function.identity()));
		var plantoesEnvolvidos = plantoes.findComSetorByIdIn(ids(alertas.stream()
						.flatMap(a -> Stream.of(a.getPlantaoId(), a.getPlantaoRelacionadoId()))))
				.stream().map(PlantaoResumo::de)
				.collect(Collectors.toMap(PlantaoResumo::id, Function.identity()));

		return alertas.stream()
				.map(a -> new InconformidadeResposta(a.getId(), a.getTipo(), a.getGravidade(), a.getData(),
						pessoas.get(a.getColaboradorId()), buscar(plantoesEnvolvidos, a.getPlantaoId()),
						buscar(plantoesEnvolvidos, a.getPlantaoRelacionadoId()), a.getValorMinutos(),
						a.getLimiteMinutos(), a.getDescricao()))
				.sorted(Comparator.comparing(InconformidadeResposta::gravidade)
						.thenComparing(InconformidadeResposta::data)
						.thenComparing(r -> r.colaborador() == null ? "" : r.colaborador().nome())
						.thenComparing(InconformidadeResposta::id))
				.toList();
	}

	private Analise vigente(YearMonth competencia) {
		return analises.findByCompetenciaAndVigenteTrue(competencia.atDay(1))
				.orElseThrow(() -> new AnaliseNaoEncontradaException(competencia));
	}

	private static List<Long> ids(Stream<Long> ids) {
		return ids.filter(Objects::nonNull).distinct().toList();
	}

	private static PlantaoResumo buscar(Map<Long, PlantaoResumo> plantoes, Long id) {
		return id == null ? null : plantoes.get(id);
	}
}
