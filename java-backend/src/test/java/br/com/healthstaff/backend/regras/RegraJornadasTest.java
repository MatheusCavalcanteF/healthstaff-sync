package br.com.healthstaff.backend.regras;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegraJornadasTest {

	static final YearMonth OUTUBRO = YearMonth.of(2026, 10);
	final RegraJornadas regra = new RegraJornadas(RegrasProperties.padrao());

	long sequencia = 1;

	/** Plantão do colaborador 1 no setor 1: "dd hh:mm" -> "dd hh:mm" de outubro/2026 (dia > 31 = novembro). */
	PlantaoAgendado p(String inicio, String fim) {
		return p(1, 1, inicio, fim);
	}

	PlantaoAgendado p(long colaborador, long setor, String inicio, String fim) {
		var de = momento(inicio);
		return new PlantaoAgendado(sequencia++, colaborador, setor, de.toLocalDate(), de, momento(fim));
	}

	static LocalDateTime momento(String texto) {
		var partes = texto.split("[ :]");
		int dia = Integer.parseInt(partes[0]);
		var base = dia > 31 ? LocalDateTime.of(2026, 11, dia - 31, 0, 0)
				: dia < 1 ? LocalDateTime.of(2026, 9, 30 + dia, 0, 0)
				: LocalDateTime.of(2026, 10, dia, 0, 0);
		return base.withHour(Integer.parseInt(partes[1])).withMinute(Integer.parseInt(partes[2]));
	}

	List<Achado> avaliar(PlantaoAgendado... plantoes) {
		return regra.avaliar(List.of(plantoes), OUTUBRO);
	}

	@Test
	void escala12x36RegularNaoGeraAlerta() {
		var plantoes = new ArrayList<PlantaoAgendado>();
		for (int dia = 1; dia <= 31; dia += 2) {
			plantoes.add(p(dia + " 07:00", dia + " 19:00"));
		}
		assertThat(regra.avaliar(plantoes, OUTUBRO)).isEmpty();
	}

	@Test
	void noturnosEmDiasAlternadosNaoGeramAlerta() {
		assertThat(avaliar(p("1 19:00", "2 07:00"), p("3 19:00", "4 07:00"), p("5 19:00", "6 07:00"))).isEmpty();
	}

	@ParameterizedTest(name = "fim {0} -> início {1}: descanso {2} min, viola={3}")
	@CsvSource({
			"1 19:00, 2 06:00, 660, false", // exatamente 11h: permitido
			"1 19:00, 2 05:59, 659, true", // 10h59
			"1 19:00, 2 07:00, 720, false", // PN seguido de PD com 12h de folga
			"1 23:00, 2 07:00, 480, true", // 8h
	})
	void limiteDeOnzeHoras(String fimAnterior, String inicioSeguinte, int descanso, boolean viola) {
		var anterior = p("1 13:00", fimAnterior);
		var inicio = momento(inicioSeguinte);
		var seguinte = new PlantaoAgendado(sequencia++, 1, 1, inicio.toLocalDate(), inicio, inicio.plusHours(6));
		var achados = avaliar(anterior, seguinte);

		if (viola) {
			assertThat(achados).singleElement().satisfies(a -> {
				assertThat(a.tipo()).isEqualTo(TipoInconformidade.INTERJORNADA);
				assertThat(a.valorMinutos()).isEqualTo(descanso);
				assertThat(a.limiteMinutos()).isEqualTo(660);
				assertThat(a.plantaoId()).isEqualTo(seguinte.id());
				assertThat(a.plantaoRelacionadoId()).isEqualTo(anterior.id());
			});
		}
		else {
			assertThat(achados).isEmpty();
		}
	}

	@Test
	void mensagemLegivelParaORh() {
		var achados = avaliar(p("1 07:00", "1 19:00"), p("2 03:00", "2 09:00"));

		assertThat(achados).singleElement().extracting(Achado::descricao)
				.isEqualTo("descanso de 8h entre o fim do plantão em 01/10 19:00 e o início em 02/10 03:00 (mínimo 11h)");
	}

	@Test
	void manhaETardeEmendadasSaoUmaJornadaSoNaoInterjornada() {
		assertThat(avaliar(p("7 07:00", "7 13:00"), p("7 13:00", "7 19:00"))).isEmpty();
	}

	@Test
	void pausaCurtaEntrePlantoesEhIntrajornada() {
		// 1h de intervalo (até 2h = mesma jornada): 07-12 e 13-18 = jornada de 11h, sem alerta.
		assertThat(avaliar(p("7 07:00", "7 12:00"), p("7 13:00", "7 18:00"))).isEmpty();
	}

	@Test
	void dobraPdMaisPnEhJornadaExcessiva() {
		var pd = p("10 07:00", "10 19:00");
		var pn = p("10 19:00", "11 07:00");

		assertThat(avaliar(pd, pn)).singleElement().satisfies(a -> {
			assertThat(a.tipo()).isEqualTo(TipoInconformidade.JORNADA_EXCESSIVA);
			assertThat(a.valorMinutos()).isEqualTo(24 * 60);
			assertThat(a.limiteMinutos()).isEqualTo(12 * 60);
			assertThat(a.plantaoId()).isEqualTo(pd.id());
			assertThat(a.plantaoRelacionadoId()).isEqualTo(pn.id());
			assertThat(a.descricao()).contains("24h", "2 plantões emendados");
		});
	}

	@Test
	void plantaoUnicoDeDozeHorasNaoEhExcessivo() {
		assertThat(avaliar(p("10 07:00", "10 19:00"))).isEmpty();
	}

	@Test
	void sobreposicaoNoMesmoSetor() {
		var primeiro = p("5 07:00", "5 19:00");
		var segundo = p("5 13:00", "5 19:00");

		var achados = avaliar(primeiro, segundo);

		assertThat(achados).extracting(Achado::tipo).contains(TipoInconformidade.SOBREPOSICAO_PLANTOES);
		assertThat(achados).filteredOn(a -> a.tipo() == TipoInconformidade.SOBREPOSICAO_PLANTOES)
				.singleElement().satisfies(a -> {
					assertThat(a.valorMinutos()).isEqualTo(6 * 60);
					assertThat(a.plantaoId()).isEqualTo(segundo.id());
					assertThat(a.plantaoRelacionadoId()).isEqualTo(primeiro.id());
					assertThat(a.descricao()).doesNotContain("outro setor");
				});
	}

	@Test
	void folguistaEscaladoEmDoisSetoresAoMesmoTempo() {
		var uti = p(7, 1, "12 19:00", "13 07:00");
		var neo = p(7, 2, "12 19:00", "13 07:00");

		assertThat(avaliar(uti, neo)).filteredOn(a -> a.tipo() == TipoInconformidade.SOBREPOSICAO_PLANTOES)
				.singleElement()
				.satisfies(a -> assertThat(a.descricao()).contains("em outro setor"));
	}

	@Test
	void folguistaQueTrocaDeSetorSemDescanso() {
		// PN na UTI e, 4h depois, PD na neonatal: a regra olha a pessoa, não o setor.
		var achados = avaliar(p(7, 1, "12 19:00", "13 07:00"), p(7, 2, "13 11:00", "13 23:00"));

		assertThat(achados).singleElement().extracting(Achado::tipo).isEqualTo(TipoInconformidade.INTERJORNADA);
	}

	@Test
	void colaboradoresDiferentesNaoSeMisturam() {
		assertThat(avaliar(p(1, 1, "5 07:00", "5 19:00"), p(2, 1, "5 07:00", "5 19:00"))).isEmpty();
	}

	@Test
	void viradaDoMesConsideraPlantaoAnteriorMasNaoDuplicaAlerta() {
		var ultimoDeSetembro = p("0 19:00", "1 07:00"); // 30/09 19:00 -> 01/10 07:00
		var primeiroDeOutubro = p("1 13:00", "1 19:00");
		var ultimoDeOutubro = p("31 19:00", "32 07:00"); // 31/10 -> 01/11
		var primeiroDeNovembro = p("32 13:00", "32 19:00");

		var achados = avaliar(ultimoDeSetembro, primeiroDeOutubro, ultimoDeOutubro, primeiroDeNovembro);

		// O de 01/10 é de outubro (o plantão de setembro entra só como contexto);
		// o de 01/11 é de novembro e não aparece na análise de outubro.
		assertThat(achados).singleElement().satisfies(a -> {
			assertThat(a.tipo()).isEqualTo(TipoInconformidade.INTERJORNADA);
			assertThat(a.data()).isEqualTo(primeiroDeOutubro.data());
		});
	}

	@Test
	void ordemDeEntradaNaoImporta() {
		var a = p("1 07:00", "1 19:00");
		var b = p("2 03:00", "2 09:00");

		assertThat(regra.avaliar(List.of(b, a), OUTUBRO)).isEqualTo(regra.avaliar(List.of(a, b), OUTUBRO));
	}

	@Test
	void parametrosConfiguraveis() {
		var flexivel = new RegraJornadas(new RegrasProperties(java.time.Duration.ofHours(8),
				java.time.Duration.ofHours(2), java.time.Duration.ofHours(24), java.time.Duration.ofHours(20),
				java.time.Duration.ofHours(-10), null, null));

		assertThat(flexivel.avaliar(List.of(p("10 07:00", "10 19:00"), p("10 19:00", "11 07:00"),
				p("11 16:00", "11 22:00")), OUTUBRO)).isEmpty();
	}
}
