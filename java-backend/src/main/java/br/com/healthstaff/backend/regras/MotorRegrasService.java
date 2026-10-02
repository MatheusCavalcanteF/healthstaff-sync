package br.com.healthstaff.backend.regras;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.Colaborador;
import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.regras.Analise.Gatilho;
import br.com.healthstaff.backend.regras.ConsolidacaoCalculadora.SaldoConhecido;

/**
 * Executa as regras de negócio sobre as escalas vigentes de uma competência e
 * grava o resultado como uma nova análise vigente (a anterior vira histórico).
 *
 * <p>Entram todos os setores: a interjornada e a sobreposição são avaliadas
 * por pessoa (folguistas trabalham em vários setores). Plantões dos dois dias
 * vizinhos ao mês entram como contexto para a virada do mês.
 *
 * <p>Sem escala vigente na competência, nada é gravado: uma análise "sem
 * problemas" de um mês sem escala seria enganosa.
 */
@Service
public class MotorRegrasService {

	private static final Logger log = LoggerFactory.getLogger(MotorRegrasService.class);

	/** Resumo de uma análise: quantos alertas de cada tipo. */
	public record ResumoAnalise(long analiseId, YearMonth competencia, Gatilho gatilho,
			int colaboradoresAnalisados, int totalInconformidades, Map<TipoInconformidade, Long> porTipo) {
	}

	private final AgendaRepository agenda;
	private final ColaboradorRepository colaboradores;
	private final AnaliseRepository analises;
	private final InconformidadeRepository inconformidades;
	private final ConsolidacaoHorasRepository consolidacoes;
	private final RegraJornadas regraJornadas;
	private final ConsolidacaoCalculadora calculadora;

	public MotorRegrasService(AgendaRepository agenda, ColaboradorRepository colaboradores, AnaliseRepository analises,
			InconformidadeRepository inconformidades, ConsolidacaoHorasRepository consolidacoes,
			RegrasProperties regras) {
		this.agenda = agenda;
		this.colaboradores = colaboradores;
		this.analises = analises;
		this.inconformidades = inconformidades;
		this.consolidacoes = consolidacoes;
		this.regraJornadas = new RegraJornadas(regras);
		this.calculadora = new ConsolidacaoCalculadora(regras);
	}

	@Transactional
	public Optional<ResumoAnalise> analisar(YearMonth competencia, Gatilho gatilho, UUID importacaoId) {
		var inicio = competencia.atDay(1);
		var fim = competencia.atEndOfMonth();

		var marcacoesDoMes = agenda.marcacoesVigentes(inicio, fim);
		if (marcacoesDoMes.isEmpty()) {
			log.info("competência {} sem escala vigente: nada a analisar ({})", competencia, gatilho);
			return Optional.empty();
		}

		// 1. Jornadas: plantões do mês + 2 dias de cada lado (virada do mês).
		var plantoes = agenda.plantoesVigentes(inicio.minusDays(2).atStartOfDay(), fim.plusDays(3).atStartOfDay());
		var achados = new ArrayList<>(regraJornadas.avaliar(plantoes, competencia));

		// 2. Consolidação de horas por colaborador escalado no mês.
		var porColaborador = marcacoesDoMes.stream()
				.collect(Collectors.groupingBy(MarcacaoDia::colaboradorId, TreeMap::new, Collectors.toList()));
		var jornadas = colaboradores.findAllById(porColaborador.keySet()).stream()
				.collect(Collectors.toMap(Colaborador::getId, Function.identity()));
		var saldos = agenda.saldosMaisRecentes(fim).stream()
				.collect(Collectors.toMap(AgendaRepository.SaldoMaisRecente::getColaboradorId,
						s -> new SaldoConhecido(s.getMinutos(), s.getDataReferencia())));

		var resultados = new ArrayList<ConsolidacaoCalculadora.Resultado>();
		porColaborador.forEach((colaboradorId, marcacoes) -> {
			var colaborador = jornadas.get(colaboradorId);
			var resultado = calculadora.consolidar(colaboradorId,
					colaborador == null ? null : colaborador.getJornada(),
					colaborador == null ? null : colaborador.getCargo(),
					marcacoes, saldos.get(colaboradorId), competencia);
			resultados.add(resultado);
			achados.addAll(resultado.achados());
		});

		// 3. Nova análise vigente. A anterior deixa de valer antes do INSERT (índice único parcial).
		analises.findByCompetenciaAndVigenteTrue(inicio).ifPresent(anterior -> {
			anterior.substituir();
			analises.saveAndFlush(anterior);
		});
		var analise = analises.save(new Analise(competencia, gatilho, importacaoId, porColaborador.size(), achados.size()));
		consolidacoes.saveAll(resultados.stream().map(r -> new ConsolidacaoHoras(analise.getId(), r)).toList());
		inconformidades.saveAll(achados.stream().map(a -> new Inconformidade(analise.getId(), a)).toList());

		var porTipo = achados.stream().collect(Collectors.groupingBy(Achado::tipo, TreeMap::new, Collectors.counting()));
		log.info("análise {} de {} ({}): {} colaboradores, {} inconformidades {}", analise.getId(), competencia,
				gatilho, porColaborador.size(), achados.size(), porTipo);
		return Optional.of(
				new ResumoAnalise(analise.getId(), competencia, gatilho, porColaborador.size(), achados.size(), porTipo));
	}
}
