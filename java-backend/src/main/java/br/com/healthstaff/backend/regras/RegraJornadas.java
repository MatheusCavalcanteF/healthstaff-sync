package br.com.healthstaff.backend.regras;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Regras de jornada sobre os plantões de cada colaborador, somando todos os
 * setores (o folguista trabalha em vários):
 *
 * <ol>
 * <li>Plantões sobrepostos -> {@link TipoInconformidade#SOBREPOSICAO_PLANTOES}.</li>
 * <li>Plantões separados por até {@code intervaloMaximoMesmaJornada} (inclusive
 * emendados, ex.: "M/T" ou PD seguido de PN) formam uma única jornada; acima de
 * {@code jornadaMaxima} -> {@link TipoInconformidade#JORNADA_EXCESSIVA}.</li>
 * <li>Descanso entre o fim de uma jornada e o início da seguinte abaixo de
 * {@code descansoMinimoInterjornada} -> {@link TipoInconformidade#INTERJORNADA}.</li>
 * </ol>
 *
 * Cada problema pertence à competência do plantão que o causa (o que começou
 * cedo demais / a jornada que começou), para que a virada do mês não gere
 * alerta duplicado: os plantões do mês vizinho entram só como contexto.
 */
public final class RegraJornadas {

	private final RegrasProperties regras;

	public RegraJornadas(RegrasProperties regras) {
		this.regras = regras;
	}

	public List<Achado> avaliar(List<PlantaoAgendado> plantoes, YearMonth competencia) {
		var achados = new ArrayList<Achado>();
		plantoes.stream()
				.collect(Collectors.groupingBy(PlantaoAgendado::colaboradorId))
				.forEach((colaborador, doColaborador) -> avaliarColaborador(doColaborador, competencia, achados));
		achados.sort(Comparator.comparing(Achado::colaboradorId).thenComparing(Achado::data));
		return achados;
	}

	private void avaliarColaborador(List<PlantaoAgendado> plantoes, YearMonth competencia, List<Achado> achados) {
		var ordenados = plantoes.stream()
				.sorted(Comparator.comparing(PlantaoAgendado::inicio).thenComparing(PlantaoAgendado::fim))
				.toList();

		var jornadas = new ArrayList<Jornada>();
		for (var plantao : ordenados) {
			var atual = jornadas.isEmpty() ? null : jornadas.getLast();
			if (atual != null && plantao.inicio().isBefore(atual.fim)) {
				if (competencia.equals(YearMonth.from(plantao.data()))) {
					achados.add(sobreposicao(atual.ultimoQueTerminaDepoisDe(plantao.inicio()), plantao));
				}
				atual.incluir(plantao);
			}
			else if (atual != null
					&& Duration.between(atual.fim, plantao.inicio()).compareTo(regras.intervaloMaximoMesmaJornada()) <= 0) {
				atual.incluir(plantao);
			}
			else {
				jornadas.add(new Jornada(plantao));
			}
		}

		for (int i = 0; i < jornadas.size(); i++) {
			var jornada = jornadas.get(i);
			boolean daCompetencia = competencia.equals(YearMonth.from(jornada.primeiro().data()));
			if (daCompetencia && jornada.duracao().compareTo(regras.jornadaMaxima()) > 0) {
				achados.add(jornadaExcessiva(jornada));
			}
			if (i > 0 && daCompetencia) {
				var anterior = jornadas.get(i - 1);
				var descanso = Duration.between(anterior.fim, jornada.inicio);
				if (descanso.compareTo(regras.descansoMinimoInterjornada()) < 0) {
					achados.add(interjornada(anterior.ultimo(), jornada.primeiro(), descanso));
				}
			}
		}
	}

	private Achado sobreposicao(PlantaoAgendado anterior, PlantaoAgendado plantao) {
		var conflito = Duration.between(plantao.inicio(), min(anterior.fim(), plantao.fim()));
		var outroSetor = anterior.setorId() != plantao.setorId() ? " em outro setor" : "";
		return new Achado(TipoInconformidade.SOBREPOSICAO_PLANTOES, plantao.colaboradorId(), plantao.data(),
				plantao.id(), anterior.id(), (int) conflito.toMinutes(), 0,
				"plantão de %s a %s se sobrepõe por %s a outro plantão%s (%s a %s)".formatted(
						Horas.momento(plantao.inicio()), Horas.momento(plantao.fim()), Horas.formatar(conflito), outroSetor,
						Horas.momento(anterior.inicio()), Horas.momento(anterior.fim())));
	}

	private Achado jornadaExcessiva(Jornada jornada) {
		var duracao = jornada.duracao();
		return new Achado(TipoInconformidade.JORNADA_EXCESSIVA, jornada.primeiro().colaboradorId(),
				jornada.primeiro().data(), jornada.primeiro().id(), jornada.plantoes.size() > 1 ? jornada.ultimo().id() : null,
				(int) duracao.toMinutes(), (int) regras.jornadaMaxima().toMinutes(),
				"jornada de %s (%d plantões emendados, de %s a %s) acima do máximo de %s".formatted(
						Horas.formatar(duracao), jornada.plantoes.size(), Horas.momento(jornada.inicio), Horas.momento(jornada.fim),
						Horas.formatar(regras.jornadaMaxima())));
	}

	private Achado interjornada(PlantaoAgendado anterior, PlantaoAgendado seguinte, Duration descanso) {
		return new Achado(TipoInconformidade.INTERJORNADA, seguinte.colaboradorId(), seguinte.data(),
				seguinte.id(), anterior.id(), (int) descanso.toMinutes(),
				(int) regras.descansoMinimoInterjornada().toMinutes(),
				"descanso de %s entre o fim do plantão em %s e o início em %s (mínimo %s)".formatted(
						Horas.formatar(descanso), Horas.momento(anterior.fim()), Horas.momento(seguinte.inicio()),
						Horas.formatar(regras.descansoMinimoInterjornada())));
	}

	private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
		return a.isBefore(b) ? a : b;
	}

	/** Plantões emendados (ou com pausa curta) tratados como uma única jornada. */
	private static final class Jornada {

		private final List<PlantaoAgendado> plantoes = new ArrayList<>();
		private final LocalDateTime inicio;
		private LocalDateTime fim;

		Jornada(PlantaoAgendado primeiro) {
			plantoes.add(primeiro);
			inicio = primeiro.inicio();
			fim = primeiro.fim();
		}

		void incluir(PlantaoAgendado plantao) {
			plantoes.add(plantao);
			if (plantao.fim().isAfter(fim)) {
				fim = plantao.fim();
			}
		}

		PlantaoAgendado primeiro() {
			return plantoes.getFirst();
		}

		/** O plantão que termina por último (é dele o fim da jornada). */
		PlantaoAgendado ultimo() {
			return plantoes.stream().max(Comparator.comparing(PlantaoAgendado::fim)).orElseThrow();
		}

		PlantaoAgendado ultimoQueTerminaDepoisDe(LocalDateTime instante) {
			return plantoes.stream()
					.filter(p -> p.fim().isAfter(instante))
					.max(Comparator.comparing(PlantaoAgendado::fim))
					.orElseThrow();
		}

		Duration duracao() {
			return Duration.between(inicio, fim);
		}
	}
}
