package br.com.healthstaff.backend.regras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.extrator.contrato.ResultadoExtracao;
import br.com.healthstaff.backend.importacao.ImportacaoService;
import br.com.healthstaff.backend.regras.Analise.Gatilho;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

/**
 * Motor de regras sobre um Postgres 18 real. Base: resposta-exemplo do
 * extrator (VANIA 90002, folguista, PN nos dias ímpares, BH no dia 2, saldo
 * +16h22; RECEM CONTRATADA, PD nos dias pares, fora do Two). Sobre ela, cada
 * teste insere a situação irregular que quer ver detectada.
 */
@SpringBootTest(properties = "healthstaff.regras.saldo-alto=10h")
@AutoConfigureMockMvc
class MotorRegrasServiceTest extends IntegracaoComBanco {

	static final YearMonth OUTUBRO = YearMonth.of(2026, 10);

	@Autowired
	MotorRegrasService motor;

	@Autowired
	ImportacaoService importacoes;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	MockMvc mvc;

	long vania;
	long recem;

	@BeforeEach
	void importarExemplo() {
		jdbc.execute("""
				truncate table inconformidade, consolidacao_horas, analise, plantao, escala, saldo_banco_horas,
				    inconsistencia, importacao, colaborador, setor restart identity cascade""");
		var exemplo = exemplo();
		importacoes.registrar(exemplo.getBancoHoras());
		importacoes.registrar(exemplo.getEscala());
		vania = jdbc.queryForObject("select id from colaborador where matricula = '90002'", Long.class);
		recem = jdbc.queryForObject("select id from colaborador where nome = 'RECEM CONTRATADA'", Long.class);
	}

	ResultadoExtracao exemplo() {
		try {
			return jsonMapper.readValue(new ClassPathResource("contrato/resultado-extracao.exemplo.json")
					.getContentAsString(StandardCharsets.UTF_8), ResultadoExtracao.class);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Insere um plantão de trabalho na escala vigente do setor (ou em outro setor, criado na hora). */
	long inserirPlantao(long colaborador, String inicio, String fim, String setor) {
		var setorId = jdbc.queryForObject("select id from setor where nome = ?", Long.class, setor);
		var escalaId = jdbc.queryForObject(
				"select id from escala where setor_id = ? and vigente", Long.class, setorId);
		return jdbc.queryForObject("""
				insert into plantao (escala_id, setor_id, colaborador_id, data, tipo, turno, codigo_original, inicio, fim)
				values (?, ?, ?, ?::timestamp::date, 'PLANTAO', 'OUTRO', 'TESTE', ?::timestamp, ?::timestamp)
				returning id""", Long.class, escalaId, setorId, colaborador, inicio, inicio, fim);
	}

	long inserirPlantao(long colaborador, String inicio, String fim) {
		return inserirPlantao(colaborador, inicio, fim, "UTI PEDIÁTRICA");
	}

	void criarOutroSetorComEscalaVigente(String nome) {
		var setorId = jdbc.queryForObject("insert into setor (nome, chave) values (?, ?) returning id", Long.class,
				nome, nome.toUpperCase());
		var importacao = jdbc.queryForObject("select id from importacao where tipo_documento = 'ESCALA_EXCEL'", UUID.class);
		jdbc.update("insert into escala (setor_id, competencia, versao, vigente, importacao_id) values (?, '2026-10-01', 1, true, ?)",
				setorId, importacao);
	}

	Map<String, Object> inconformidade(String tipo) {
		return jdbc.queryForMap("select * from inconformidade i join analise a on a.id = i.analise_id "
				+ "where a.vigente and i.tipo = ?", tipo);
	}

	int contar(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Nested
	class Regras {

		@Test
		void escalaRegularDoExemploNaoTemProblemaDeJornada() {
			var resumo = motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			assertThat(resumo.porTipo()).doesNotContainKeys(TipoInconformidade.INTERJORNADA,
					TipoInconformidade.SOBREPOSICAO_PLANTOES, TipoInconformidade.JORNADA_EXCESSIVA);
		}

		@Test
		void dobraPnMaisPdEhJornadaExcessiva() {
			// VANIA faz PN 01/10 19:00 -> 02/10 07:00; emenda um PD no dia 2.
			var pd = inserirPlantao(vania, "2026-10-02 07:00", "2026-10-02 19:00");

			var resumo = motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			assertThat(resumo.porTipo()).containsEntry(TipoInconformidade.JORNADA_EXCESSIVA, 1L);
			var alerta = inconformidade("JORNADA_EXCESSIVA");
			assertThat(alerta).containsEntry("colaborador_id", vania)
					.containsEntry("gravidade", "ALTA")
					.containsEntry("valor_minutos", 24 * 60)
					.containsEntry("plantao_relacionado_id", pd);
		}

		@Test
		void descansoCurtoEntrePlantoesEhInterjornada() {
			// RECEM faz PD 02/10 07-19; um plantão às 03:00 do dia 3 deixa só 8h de descanso.
			var curto = inserirPlantao(recem, "2026-10-03 03:00", "2026-10-03 06:00");

			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var alerta = inconformidade("INTERJORNADA");
			assertThat(alerta).containsEntry("colaborador_id", recem)
					.containsEntry("plantao_id", curto)
					.containsEntry("valor_minutos", 8 * 60)
					.containsEntry("limite_minutos", 11 * 60);
			assertThat((String) alerta.get("descricao")).contains("02/10 19:00", "03/10 03:00");
		}

		@Test
		void folguistaEmDoisSetoresAoMesmoTempo() {
			criarOutroSetorComEscalaVigente("UTI NEONATAL");
			inserirPlantao(vania, "2026-10-03 19:00", "2026-10-04 07:00", "UTI NEONATAL"); // UTI já tem PN no dia 3

			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var alerta = inconformidade("SOBREPOSICAO_PLANTOES");
			assertThat(alerta).containsEntry("colaborador_id", vania).containsEntry("gravidade", "CRITICA");
			assertThat((String) alerta.get("descricao")).contains("em outro setor");
		}

		@Test
		void saldoAltoComEscalaAcimaDaCarga() {
			// VANIA: 16 PN = 192h contra 180h; saldo +16h22 (limite do teste: 10h).
			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var alerta = inconformidade("SALDO_POSITIVO_ESCALA_ACIMA");
			assertThat(alerta).containsEntry("colaborador_id", vania).containsEntry("valor_minutos", 982);
			assertThat((String) alerta.get("descricao")).contains("+16h22", "12h acima", "saldo projetado +28h22");
		}

		@Test
		void folgaDeBancoDeHorasComSaldoNegativo() {
			jdbc.update("update saldo_banco_horas set minutos = -243 where colaborador_id = ?", vania);

			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var alerta = inconformidade("FOLGA_BH_SEM_SALDO");
			assertThat(alerta).containsEntry("colaborador_id", vania);
			assertThat(alerta.get("data").toString()).isEqualTo("2026-10-02");
		}
	}

	@Nested
	class Consolidacao {

		@Test
		void horasPorColaboradorGravadas() {
			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var linha = jdbc.queryForMap("""
					select c.* from consolidacao_horas c join analise a on a.id = c.analise_id
					where a.vigente and c.colaborador_id = ?""", vania);
			assertThat(linha).containsEntry("jornada", "12x36")
					.containsEntry("plantoes", 16)
					.containsEntry("minutos_escalados", 192 * 60)
					.containsEntry("minutos_esperados", 180 * 60)
					.containsEntry("diferenca_minutos", 12 * 60)
					.containsEntry("saldo_minutos", 982)
					.containsEntry("saldo_projetado_minutos", 982 + 12 * 60)
					.containsEntry("folgas_banco_horas", 1)
					.containsEntry("categoria", "ENFERMEIRO")
					.containsEntry("minutos_cumpridos", 192 * 60);
			// Fora do Two: consolida as horas, sem saldo nem projeção.
			var semSaldo = jdbc.queryForMap("select * from consolidacao_horas where colaborador_id = ?", recem);
			assertThat(semSaldo.get("saldo_minutos")).isNull();
			assertThat(semSaldo.get("saldo_projetado_minutos")).isNull();
		}
	}

	@Nested
	class FolgaOrganizacional {

		@Test
		void fgDaTecnicaCreditaDozeHoras() {
			// RECEM (técnica, PD nos dias pares) tem X nos ímpares; uma delas vira FG.
			jdbc.update("update plantao set codigo_original = 'FG' where colaborador_id = ? and data = '2026-10-01'",
					recem);

			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			var linha = jdbc.queryForMap("select c.* from consolidacao_horas c join analise a on a.id = c.analise_id "
					+ "where a.vigente and c.colaborador_id = ?", recem);
			assertThat(linha).containsEntry("categoria", "TECNICO_ENFERMAGEM")
					.containsEntry("folgas_organizacionais", 1)
					.containsEntry("folgas_organizacionais_creditadas", 1)
					.containsEntry("minutos_credito_fg", 12 * 60)
					.containsEntry("minutos_cumpridos", (int) linha.get("minutos_escalados") + 12 * 60)
					.containsEntry("minutos_esperados", 180 * 60);
		}
	}

	@Nested
	class Versionamento {

		@Test
		void reanaliseViraVigenteEGuardaAAnterior() {
			inserirPlantao(recem, "2026-10-03 03:00", "2026-10-03 06:00");
			var primeira = motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();
			var segunda = motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			assertThat(contar("select count(*) from analise")).isEqualTo(2);
			assertThat(jdbc.queryForObject("select id from analise where vigente", Long.class)).isEqualTo(segunda.analiseId());
			// Histórico: as inconformidades da primeira análise continuam lá.
			assertThat(contar("select count(*) from inconformidade where analise_id = ?", primeira.analiseId()))
					.isEqualTo(primeira.totalInconformidades());
		}

		@Test
		void novaVersaoDaEscalaSubstituiOsPlantoesAnalisados() {
			inserirPlantao(recem, "2026-10-03 03:00", "2026-10-03 06:00");
			assertThat(motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow().porTipo())
					.containsKey(TipoInconformidade.INTERJORNADA);

			// Gestor corrige e reenvia a escala: só a versão vigente é analisada.
			var corrigida = exemplo().getEscala();
			corrigida.setIdLote(UUID.randomUUID());
			corrigida.getOrigem().setHashSha256("b".repeat(64));
			importacoes.registrar(corrigida);

			assertThat(motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow().porTipo())
					.doesNotContainKey(TipoInconformidade.INTERJORNADA);
		}

		@Test
		void apagarVersaoAntigaDaEscalaNaoApagaOAlerta() {
			inserirPlantao(recem, "2026-10-03 03:00", "2026-10-03 06:00");
			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			jdbc.update("delete from escala");

			var alerta = jdbc.queryForMap("select plantao_id, descricao from inconformidade where tipo = 'INTERJORNADA'");
			assertThat(alerta.get("plantao_id")).isNull();
			assertThat((String) alerta.get("descricao")).contains("8h");
		}
	}

	@Nested
	class Api {

		@Test
		void consultaAAnaliseVigenteComNomes() throws Exception {
			inserirPlantao(recem, "2026-10-03 03:00", "2026-10-03 06:00");
			motor.analisar(OUTUBRO, Gatilho.MANUAL, null).orElseThrow();

			mvc.perform(get("/api/v1/analises/2026-10"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.competencia").value("2026-10"))
					.andExpect(jsonPath("$.gatilho").value("MANUAL"))
					.andExpect(jsonPath("$.consolidacao", hasSize(2)))
					.andExpect(jsonPath("$.inconformidades[?(@.tipo == 'INTERJORNADA')].colaborador.nome")
							.value("RECEM CONTRATADA"))
					.andExpect(jsonPath("$.inconformidades[?(@.tipo == 'INTERJORNADA')].gravidade").value("ALTA"))
					.andExpect(jsonPath("$.inconformidades[?(@.tipo == 'SALDO_POSITIVO_ESCALA_ACIMA')].colaborador.matricula")
							.value("90002"));
		}

		@Test
		void reprocessaSobDemanda() throws Exception {
			mvc.perform(post("/api/v1/analises/2026-10"))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.competencia").value("2026-10"))
					.andExpect(jsonPath("$.gatilho").value("MANUAL"))
					.andExpect(jsonPath("$.colaboradoresAnalisados").value(2));
		}

		@Test
		void mesSemEscalaNaoGeraAnaliseEnganosa() throws Exception {
			assertThat(motor.analisar(YearMonth.of(2026, 11), Gatilho.MANUAL, null)).isEmpty();
			assertThat(contar("select count(*) from analise where competencia = '2026-11-01'")).isZero();

			mvc.perform(post("/api/v1/analises/2026-11"))
					.andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.codigo").value("SEM_ESCALA_VIGENTE"));
		}

		@Test
		void competenciaSemAnalise() throws Exception {
			mvc.perform(get("/api/v1/analises/2025-01"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.codigo").value("ANALISE_NAO_ENCONTRADA"));
		}

		@Test
		void competenciaInvalida() throws Exception {
			mvc.perform(get("/api/v1/analises/2026-13"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"))
					.andExpect(jsonPath("$.detail").value(containsString("AAAA-MM")));
		}
	}
}
