package br.com.healthstaff.backend.regras;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.healthstaff.backend.colaborador.CategoriaProfissional;
import br.com.healthstaff.backend.regras.ConsolidacaoCalculadora.SaldoConhecido;

class ConsolidacaoCalculadoraTest {

	static final YearMonth OUTUBRO = YearMonth.of(2026, 10);
	static final String ENFERMEIRA = "Enfermeira Diurno Ímpar";
	static final String TECNICA = "Téc. de Enf. Diurno ímpar";
	final ConsolidacaoCalculadora calculadora = new ConsolidacaoCalculadora(RegrasProperties.padrao());

	static MarcacaoDia plantao(int dia) {
		var inicio = LocalDateTime.of(2026, 10, dia, 7, 0);
		return new MarcacaoDia(1, inicio.toLocalDate(), "PLANTAO", "PD", null, inicio, inicio.plusHours(12));
	}

	static MarcacaoDia marcacao(int dia, String tipo, String codigo, String observacao) {
		return new MarcacaoDia(1, LocalDate.of(2026, 10, dia), tipo, codigo, observacao, null, null);
	}

	/** n plantões de 12h em dias ímpares. */
	static List<MarcacaoDia> plantoes(int n) {
		var lista = new ArrayList<MarcacaoDia>();
		for (int i = 0; i < n; i++) {
			lista.add(plantao(1 + 2 * i));
		}
		return lista;
	}

	static SaldoConhecido saldo(int horas) {
		return new SaldoConhecido(horas * 60, LocalDate.of(2026, 10, 1));
	}

	@Nested
	class Calculo {

		@Test
		void horasEscaladasEsperadasEProjecao() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, plantoes(16), saldo(10), OUTUBRO);

			assertThat(r.plantoes()).isEqualTo(16);
			assertThat(r.minutosEscalados()).isEqualTo(192 * 60);
			assertThat(r.minutosEsperados()).isEqualTo(180 * 60);
			assertThat(r.diferencaMinutos()).isEqualTo(12 * 60);
			assertThat(r.saldoMinutos()).isEqualTo(10 * 60);
			assertThat(r.saldoProjetadoMinutos()).isEqualTo(22 * 60);
			assertThat(r.achados()).isEmpty(); // saldo de 10h ainda não é "alto"
		}

		@Test
		void feriasReduzemACargaEsperadaProporcionalmente() {
			var marcacoes = new ArrayList<>(plantoes(8)); // dias 1..15
			for (int dia = 17; dia <= 31; dia++) {
				marcacoes.add(marcacao(dia, "FERIAS", "FÉRIAS A PARTIR DE 17/10/2026", null));
			}

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(0), OUTUBRO);

			assertThat(r.diasAusencia()).isEqualTo(15);
			assertThat(r.minutosEsperados()).isEqualTo((int) Math.round(180 * 60 * 16 / 31.0));
		}

		@Test
		void mesInteiroDeLicencaNaoEsperaHorasNemGeraAlerta() {
			var licenca = new ArrayList<MarcacaoDia>();
			for (int dia = 1; dia <= 31; dia++) {
				licenca.add(marcacao(dia, "LICENCA", "LICENÇA GESTACIONAL A PARTIR 16/03/2026", null));
			}

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, licenca, saldo(-30), OUTUBRO);

			assertThat(r.minutosEsperados()).isZero();
			assertThat(r.diferencaMinutos()).isZero();
			assertThat(r.achados()).isEmpty();
		}

		/** Férias de {@code primeiro} a {@code ultimo} de outubro; os demais dias, folga regular (X). */
		static List<MarcacaoDia> ferias(int primeiro, int ultimo) {
			var marcacoes = new ArrayList<MarcacaoDia>();
			for (int dia = 1; dia <= 31; dia++) {
				marcacoes.add(dia >= primeiro && dia <= ultimo
						? marcacao(dia, "FERIAS", "FÉRIAS A PARTIR DE 02/10/2026 ATÉ 31/10/2026", null)
						: marcacao(dia, "FOLGA", "X", null));
			}
			return marcacoes;
		}

		@Test
		void feriasQuaseOMesTodoZeramACargaSemDebitoArtificial() {
			// Caso real: X no dia 1 e férias de 02 a 31 (30 dias). Antes: carga de 5h e débito de -5h.
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, ferias(2, 31), saldo(-30), OUTUBRO);

			assertThat(r.diasAusencia()).isEqualTo(30);
			assertThat(r.minutosEsperados()).isZero();
			assertThat(r.diferencaMinutos()).isZero();
			assertThat(r.saldoProjetadoMinutos()).isEqualTo(-30 * 60);
			assertThat(r.achados()).isEmpty();
		}

		@ParameterizedTest(name = "{0} dias de ausência -> carga esperada {1} min")
		@CsvSource({
				"28, 0", // limite: zera
				"29, 0",
				"31, 0",
				"27, 1394", // abaixo do limite: proporcional (180h x 4/31)
				"15, 5574", // 180h x 16/31
		})
		void limiteParaZerarACarga(int diasAusencia, int esperados) {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, ferias(1, diasAusencia), saldo(0), OUTUBRO);

			assertThat(r.diasAusencia()).isEqualTo(diasAusencia);
			assertThat(r.minutosEsperados()).isEqualTo(esperados);
		}

		@Test
		void plantaoTrabalhadoNoMesDeFeriasContaComoCumprido() {
			var marcacoes = new ArrayList<>(ferias(1, 29));
			marcacoes.add(plantao(31));

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(0), OUTUBRO);

			assertThat(r.minutosEsperados()).isZero();
			assertThat(r.minutosCumpridos()).isEqualTo(12 * 60);
			assertThat(r.diferencaMinutos()).isEqualTo(12 * 60);
		}

		@Test
		void fevereiroInteiroDeLicenca() {
			var marcacoes = new ArrayList<MarcacaoDia>();
			for (int dia = 1; dia <= 28; dia++) {
				marcacoes.add(new MarcacaoDia(1, LocalDate.of(2027, 2, dia), "LICENCA", "LM", null, null, null));
			}

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(0), YearMonth.of(2027, 2));

			assertThat(r.minutosEsperados()).isZero();
		}

		@Test
		void limiteConfiguravel() {
			var regras = new RegrasProperties(Duration.ofHours(11), Duration.ofHours(2), Duration.ofHours(12),
					Duration.ofHours(20), Duration.ofHours(-10), null, null, 31);

			var r = new ConsolidacaoCalculadora(regras)
					.consolidar(1, "12x36", ENFERMEIRA, ferias(2, 31), saldo(0), OUTUBRO);

			assertThat(r.minutosEsperados()).isEqualTo((int) Math.round(180 * 60 / 31.0));
		}

		@Test
		void faltaNaoReduzACargaEsperada() {
			var marcacoes = new ArrayList<>(plantoes(14));
			marcacoes.add(marcacao(30, "FALTA", "FA", null));

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(0), OUTUBRO);

			assertThat(r.diasAusencia()).isZero();
			assertThat(r.minutosEsperados()).isEqualTo(180 * 60);
		}

		@Test
		void jornadaDesconhecidaConsolidaSemComparar() {
			var r = calculadora.consolidar(1, null, ENFERMEIRA, plantoes(16), saldo(50), OUTUBRO);

			assertThat(r.minutosEscalados()).isEqualTo(192 * 60);
			assertThat(r.minutosEsperados()).isNull();
			assertThat(r.saldoProjetadoMinutos()).isNull();
			assertThat(r.achados()).isEmpty();
		}

		@Test
		void semSaldoNoTwoNaoProjetaNemAlerta() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, plantoes(16), null, OUTUBRO);

			assertThat(r.saldoMinutos()).isNull();
			assertThat(r.saldoProjetadoMinutos()).isNull();
			assertThat(r.achados()).isEmpty();
		}

		@Test
		void marcacoesDeOutroMesSaoIgnoradas() {
			var setembro = new MarcacaoDia(1, LocalDate.of(2026, 9, 30), "PLANTAO", "PN", null,
					LocalDateTime.of(2026, 9, 30, 19, 0), LocalDateTime.of(2026, 10, 1, 7, 0));

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, List.of(setembro, plantao(2)), null, OUTUBRO);

			assertThat(r.plantoes()).isEqualTo(1);
			assertThat(r.minutosEscalados()).isEqualTo(12 * 60);
		}
	}

	@Nested
	class Alertas {

		@Test
		void saldoAltoComEscalaAcimaDaCarga() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, plantoes(16), saldo(44), OUTUBRO);

			assertThat(r.achados()).singleElement().satisfies(a -> {
				assertThat(a.tipo()).isEqualTo(TipoInconformidade.SALDO_POSITIVO_ESCALA_ACIMA);
				assertThat(a.valorMinutos()).isEqualTo(44 * 60);
				assertThat(a.limiteMinutos()).isEqualTo(20 * 60);
				assertThat(a.data()).isEqualTo(LocalDate.of(2026, 10, 31));
				assertThat(a.descricao()).isEqualTo("saldo de banco de horas de +44h e escala 12h acima da carga do mês "
						+ "(192h de plantão; 180h previstas); saldo projetado +56h");
			});
		}

		@Test
		void saldoAltoComEscalaNaCargaNaoAlerta() {
			assertThat(calculadora.consolidar(1, "12x36", ENFERMEIRA, plantoes(15), saldo(44), OUTUBRO).achados()).isEmpty();
		}

		@Test
		void saldoNegativoComEscalaAbaixoDaCarga() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, plantoes(13), saldo(-15), OUTUBRO);

			assertThat(r.achados()).singleElement().satisfies(a -> {
				assertThat(a.tipo()).isEqualTo(TipoInconformidade.SALDO_NEGATIVO_ESCALA_ABAIXO);
				assertThat(a.descricao()).contains("-15h", "24h abaixo", "saldo projetado -39h");
			});
		}

		@ParameterizedTest(name = "saldo {0}h -> alerta de folga BH: {1}")
		@CsvSource({ "-4, true", "0, true", "1, false", "30, false" })
		void folgaDeBancoDeHorasSemSaldo(int saldoHoras, boolean alerta) {
			var marcacoes = new ArrayList<>(plantoes(15));
			marcacoes.add(marcacao(2, "FOLGA", "BH", "compensação de banco de horas"));

			var achados = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(saldoHoras), OUTUBRO).achados();

			assertThat(achados).filteredOn(a -> a.tipo() == TipoInconformidade.FOLGA_BH_SEM_SALDO)
					.hasSize(alerta ? 1 : 0);
		}

		@Test
		void folgaComumNaoEhFolgaDeBancoDeHoras() {
			var marcacoes = new ArrayList<>(plantoes(15));
			marcacoes.add(marcacao(2, "FOLGA", "X", null));
			marcacoes.add(marcacao(4, "FOLGA", "FG", null));

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(-4), OUTUBRO);

			assertThat(r.folgasBancoHoras()).isZero();
			assertThat(r.achados()).isEmpty();
		}
	}

	@Nested
	class CargaContratual {

		@ParameterizedTest(name = "{0} -> {1}h")
		@CsvSource({ "12x36, 180", "12X36, 180", "12 x 36, 180", "40H, 200", "30h, 150", "36H, 180" })
		void cargasConhecidas(String jornada, long horas) {
			assertThat(RegrasProperties.padrao().cargaMensal(jornada)).contains(Duration.ofHours(horas));
		}

		@ParameterizedTest
		@CsvSource({ "''", "PLANTONISTA", "6x1" })
		void jornadasNaoReconhecidas(String jornada) {
			assertThat(RegrasProperties.padrao().cargaMensal(jornada)).isEmpty();
		}

		@Test
		void cargaConfiguradaSobrepoeAPadrao() {
			var regras = new RegrasProperties(Duration.ofHours(11), Duration.ofHours(2), Duration.ofHours(12),
					Duration.ofHours(20), Duration.ofHours(-10),
					java.util.Map.of("12x36", Duration.ofHours(192), "6X1", Duration.ofHours(220)), null, null);

			assertThat(regras.cargaMensal("12X36")).contains(Duration.ofHours(192));
			assertThat(regras.cargaMensal("6x1")).contains(Duration.ofHours(220));
		}
	}

	@Nested
	class FolgasOrganizacionais {

		static List<MarcacaoDia> comFg(int plantoes, int... diasFg) {
			var marcacoes = new ArrayList<>(plantoes(plantoes));
			for (int dia : diasFg) {
				marcacoes.add(marcacao(dia, "FOLGA", "FG", null));
			}
			return marcacoes;
		}

		@Test
		void fgCreditaJornadaSemReduzirACargaContratual() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, comFg(13, 2, 4), saldo(0), OUTUBRO);

			assertThat(r.categoria()).isEqualTo(CategoriaProfissional.ENFERMEIRO);
			assertThat(r.minutosEscalados()).isEqualTo(156 * 60); // só plantão
			assertThat(r.folgasOrganizacionais()).isEqualTo(2);
			assertThat(r.folgasOrganizacionaisCreditadas()).isEqualTo(2);
			assertThat(r.minutosCreditoFg()).isEqualTo(24 * 60);
			assertThat(r.minutosCumpridos()).isEqualTo(180 * 60);
			assertThat(r.minutosEsperados()).isEqualTo(180 * 60); // contrato intacto
			assertThat(r.diferencaMinutos()).isZero();
		}

		@Test
		void enfermeiraComDuasFgESaldoNegativoNaoTemMaisFalsoDebito() {
			// Antes: 13 plantões = 156h contra 180h -> "24h abaixo" e alerta de saldo negativo.
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, comFg(13, 2, 4), saldo(-15), OUTUBRO);

			assertThat(r.diferencaMinutos()).isZero();
			assertThat(r.saldoProjetadoMinutos()).isEqualTo(-15 * 60);
			assertThat(r.achados()).isEmpty();
		}

		@Test
		void tecnicaTemCotaDeUmaFg() {
			var r = calculadora.consolidar(1, "12x36", TECNICA, comFg(14, 2), saldo(0), OUTUBRO);

			assertThat(r.categoria()).isEqualTo(CategoriaProfissional.TECNICO_ENFERMAGEM);
			assertThat(r.minutosCumpridos()).isEqualTo(180 * 60);
			assertThat(r.diferencaMinutos()).isZero();
		}

		@Test
		void fgAlemDaCotaNaoEhCreditada() {
			// Técnica com 2 FG: só 1 credita; a segunda não pode esconder o débito.
			var r = calculadora.consolidar(1, "12x36", TECNICA, comFg(13, 2, 4), saldo(-15), OUTUBRO);

			assertThat(r.folgasOrganizacionais()).isEqualTo(2);
			assertThat(r.folgasOrganizacionaisCreditadas()).isEqualTo(1);
			assertThat(r.minutosCumpridos()).isEqualTo(168 * 60);
			assertThat(r.diferencaMinutos()).isEqualTo(-12 * 60);
			assertThat(r.achados()).singleElement().satisfies(a -> {
				assertThat(a.tipo()).isEqualTo(TipoInconformidade.SALDO_NEGATIVO_ESCALA_ABAIXO);
				assertThat(a.descricao()).contains("12h abaixo",
						"168h cumpridas = 156h de plantão + 12h de 1 FG; 180h previstas");
			});
		}

		@Test
		void administrativoTemCotaDeUmaFg() {
			var r = calculadora.consolidar(1, "12x36", "AUX. ADMINISTRATIVO FOLGUISTA", comFg(14, 2, 4), saldo(0),
					OUTUBRO);

			assertThat(r.categoria()).isEqualTo(CategoriaProfissional.ADMINISTRATIVO);
			assertThat(r.folgasOrganizacionaisCreditadas()).isEqualTo(1);
		}

		@Test
		void cargoNaoReconhecidoUsaACotaMaisConservadora() {
			var r = calculadora.consolidar(1, "12x36", "Fisioterapeuta", comFg(14, 2, 4), saldo(0), OUTUBRO);

			assertThat(r.categoria()).isEqualTo(CategoriaProfissional.OUTRO);
			assertThat(r.folgasOrganizacionaisCreditadas()).isEqualTo(1);
		}

		@Test
		void fgEntraNaCargaCumpridaParaOAlertaDeSaldoAlto() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, comFg(15, 2, 4), saldo(44), OUTUBRO);

			assertThat(r.achados()).singleElement().satisfies(a -> {
				assertThat(a.tipo()).isEqualTo(TipoInconformidade.SALDO_POSITIVO_ESCALA_ACIMA);
				assertThat(a.descricao()).isEqualTo("saldo de banco de horas de +44h e escala 24h acima da carga do mês "
						+ "(204h cumpridas = 180h de plantão + 24h de 2 FG; 180h previstas); saldo projetado +68h");
			});
		}

		@Test
		void mesmaFgRepetidaNoDiaContaUmaVez() {
			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, comFg(14, 2, 2), saldo(0), OUTUBRO);

			assertThat(r.folgasOrganizacionais()).isEqualTo(1);
		}

		@Test
		void outrasFolgasNaoCreditam() {
			var marcacoes = new ArrayList<>(plantoes(14));
			marcacoes.add(marcacao(2, "FOLGA", "X", null));
			marcacoes.add(marcacao(4, "FOLGA", "BH", "compensação de banco de horas"));

			var r = calculadora.consolidar(1, "12x36", ENFERMEIRA, marcacoes, saldo(5), OUTUBRO);

			assertThat(r.folgasOrganizacionais()).isZero();
			assertThat(r.minutosCumpridos()).isEqualTo(r.minutosEscalados());
		}

		@Test
		void cotasECreditoConfiguraveis() {
			var regras = new RegrasProperties(Duration.ofHours(11), Duration.ofHours(2), Duration.ofHours(12),
					Duration.ofHours(20), Duration.ofHours(-10), null,
					new RegrasProperties.FolgaOrganizacional(Duration.ofHours(6), 3, 2, 0, 0), null);

			var r = new ConsolidacaoCalculadora(regras)
					.consolidar(1, "12x36", TECNICA, comFg(14, 2, 4, 6), saldo(0), OUTUBRO);

			assertThat(r.folgasOrganizacionaisCreditadas()).isEqualTo(2);
			assertThat(r.minutosCreditoFg()).isEqualTo(12 * 60);
		}
	}

	@Nested
	class Categoria {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({
				"Enfermeira Diurno Ímpar, ENFERMEIRO",
				"Enfermeira Noturno Par, ENFERMEIRO",
				"Enfª Folguista Diurna, ENFERMEIRO",
				"COORD. ENFERMAGEM, ENFERMEIRO",
				"ENF 180H UTI PED HX, ENFERMEIRO",
				"Téc. de Enf. Diurno ímpar, TECNICO_ENFERMAGEM",
				"Téc. de Enf. Noturno Folguista, TECNICO_ENFERMAGEM",
				"Técnica de Enfermagem, TECNICO_ENFERMAGEM",
				"AUXILIAR ADMINISTRATIVO, ADMINISTRATIVO",
				"AUX. ADMINISTRATIVO FERISTA, ADMINISTRATIVO",
				"Fisioterapeuta, OUTRO",
				"'', OUTRO",
		})
		void cargosReaisDaEscala(String cargo, CategoriaProfissional esperada) {
			assertThat(CategoriaProfissional.de(cargo)).isEqualTo(esperada);
		}

		@Test
		void cargoNulo() {
			assertThat(CategoriaProfissional.de(null)).isEqualTo(CategoriaProfissional.OUTRO);
		}
	}
}
