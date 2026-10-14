package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.RelogioDeTeste;
import br.com.healthstaff.backend.extrator.ArquivoParaExtracao;
import br.com.healthstaff.backend.extrator.ExtratorClient;
import br.com.healthstaff.backend.extrator.ExtratorFalso;
import br.com.healthstaff.backend.extrator.ExtratorProperties;
import br.com.healthstaff.backend.extrator.SolicitacaoExtracao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /actuator/health} é livre, mas só o ADMIN vê os componentes: o
 * detalhe do extrator nunca traz a URL interna. O resultado do extrator vale
 * 10s para qualquer requisição e só uma consulta roda por vez. O extrator é
 * um servidor HTTP falso, que conta as consultas recebidas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class HealthcheckTest extends IntegracaoComBanco {

	static final ExtratorFalso EXTRATOR = new ExtratorFalso();

	/**
	 * Tudo o que um anônimo ou um perfil não-ADMIN recebe: o status geral e os
	 * nomes dos grupos de probes (ligadas por padrão no Spring Boot 4), sem
	 * nenhum componente. Comparado por inteiro: campo novo aqui quebra o teste.
	 */
	static final String SO_STATUS = "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}";

	@TestConfiguration(proxyBeanMethods = false)
	static class Relogio {

		/** O resultado guardado pelo indicador do extrator só vence quando o teste avança este relógio. */
		@Bean
		RelogioDeTeste relogio() {
			return new RelogioDeTeste(Instant.now());
		}
	}

	@DynamicPropertySource
	static void extrator(DynamicPropertyRegistry registro) {
		registro.add("healthstaff.extrator.url", EXTRATOR::url);
	}

	@AfterAll
	static void encerrar() {
		EXTRATOR.close();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	UsuarioRepository usuarios;

	@Autowired
	TokenService tokens;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RelogioDeTeste relogio;

	/** Real, salvo quando um teste simula uma falha inesperada (o Mockito desfaz após cada teste). */
	@MockitoSpyBean
	ExtratorClient cliente;

	@Autowired
	ExtratorProperties propriedades;

	@BeforeEach
	void preparar() {
		jdbc.update("delete from usuario");
		EXTRATOR.reiniciar();
		EXTRATOR.responder(200, "{\"status\":\"ok\",\"versao\":\"1.0\"}");
		// Vence o resultado guardado por outro teste: a primeira requisição consulta o extrator.
		relogio.avancar(Duration.ofMinutes(1));
	}

	String bearer(Perfil perfil) {
		var usuario = UsuariosDeTeste.obter(usuarios, perfil.name().toLowerCase() + "@teste.local", perfil);
		return "Bearer " + tokens.emitir(usuario).token();
	}

	@Test
	void anonimoVeSoOStatusGeral() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(content().json(SO_STATUS, JsonCompareMode.STRICT));
	}

	@ParameterizedTest
	@EnumSource(value = Perfil.class, names = { "AUDITOR", "GESTOR_RH" })
	void perfisSemSerAdminVeemSoOStatusGeral(Perfil perfil) throws Exception {
		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(perfil)))
				.andExpect(status().isOk())
				.andExpect(content().json(SO_STATUS, JsonCompareMode.STRICT));
	}

	@Test
	void componenteNaoPodeSerConsultadoSozinhoSemSerAdmin() throws Exception {
		mvc.perform(get("/actuator/health/extrator")).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/health/extrator").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.AUDITOR)))
				.andExpect(status().isNotFound());
		assertThat(EXTRATOR.recebidas()).isZero();
	}

	@Test
	void adminVeOsComponentesEODetalheDoExtrator() throws Exception {
		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.db.status").value("UP"))
				.andExpect(jsonPath("$.components.extrator.status").value("UP"))
				.andExpect(jsonPath("$.components.extrator.details.versao").value("1.0"));
	}

	@Test
	void extratorForaDoArNaoExpoeAUrlInternaNemAoAdmin(CapturedOutput saida) throws Exception {
		EXTRATOR.atrasar(Duration.ofSeconds(10)); // travado: cai no timeout-saude

		var corpo = mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.components.extrator.status").value("DOWN"))
				.andExpect(jsonPath("$.components.extrator.details.erro")
						.value("extrator inacessível; detalhes no log do backend"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(corpo).doesNotContain(EXTRATOR.url()).doesNotContain("127.0.0.1");
		// A mensagem completa, com a URL, fica no log para a operação.
		assertThat(saida).contains("healthcheck do extrator falhou").contains(EXTRATOR.url());
	}

	@Test
	void erroInesperadoNoClienteViraDownGenericoEFicaEmCache(CapturedOutput saida) throws Exception {
		doThrow(new IllegalStateException("falha inesperada em http://extrator-interno.local:8000/health"))
				.when(cliente).saude();

		var corpo = mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.components.extrator.status").value("DOWN"))
				.andExpect(jsonPath("$.components.extrator.details.erro")
						.value("extrator inacessível; detalhes no log do backend"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(corpo).doesNotContain("extrator-interno.local");
		assertThat(saida).contains("http://extrator-interno.local:8000/health");

		// O DOWN vale os mesmos 10s: a segunda requisição não chama o cliente de novo.
		relogio.avancar(Duration.ofSeconds(9));
		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(jsonPath("$.components.extrator.details.erro")
						.value("extrator inacessível; detalhes no log do backend"));
		verify(cliente, times(1)).saude();
	}

	@Test
	void resultadoValeDezSegundosParaQualquerRequisicao() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		assertThat(EXTRATOR.recebidas()).isEqualTo(1);

		// Dentro dos 10s, nem parâmetro aleatório na URL nem token fazem consultar de novo.
		relogio.avancar(Duration.ofSeconds(9));
		mvc.perform(get("/actuator/health").param("x", UUID.randomUUID().toString())).andExpect(status().isOk());
		mvc.perform(get("/actuator/health").param("x", UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(jsonPath("$.components.extrator.status").value("UP"));
		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.AUDITOR)))
				.andExpect(status().isOk());
		assertThat(EXTRATOR.recebidas()).isEqualTo(1);

		relogio.avancar(Duration.ofSeconds(1));
		mvc.perform(get("/actuator/health").param("x", UUID.randomUUID().toString())).andExpect(status().isOk());
		assertThat(EXTRATOR.recebidas()).isEqualTo(2);
	}

	record Resposta(int status, Duration tempo) {
	}

	@Test
	void extratorTravadoCom20RequisicoesSimultaneasGeraUmaChamadaSo() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // UP guardado
		relogio.avancar(Duration.ofSeconds(11)); // venceu...
		EXTRATOR.atrasar(Duration.ofSeconds(10)); // ...e o extrator travou

		var respostas = new ArrayList<Resposta>();
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var largada = new CountDownLatch(1);
			var pendentes = IntStream.range(0, 20).mapToObj(i -> threads.submit(() -> {
				largada.await();
				var inicio = System.nanoTime();
				int status = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
				return new Resposta(status, Duration.ofNanos(System.nanoTime() - inicio));
			})).toList();
			largada.countDown();
			for (var pendente : pendentes) {
				respostas.add(pendente.get(20, TimeUnit.SECONDS));
			}
		}

		// A carga do UP + uma única consulta nova, apesar das 20 requisições.
		assertThat(EXTRATOR.recebidas()).isEqualTo(2);
		// Quem consultou esperou o timeout-saude (3s) e viu o DOWN; as outras 19 receberam o
		// último resultado (UP) sem esperar a consulta.
		assertThat(respostas).filteredOn(r -> r.status() == 503).singleElement()
				.satisfies(r -> assertThat(r.tempo()).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(10)));
		assertThat(respostas).filteredOn(r -> r.status() == 200).hasSize(19)
				.allSatisfy(r -> assertThat(r.tempo()).isLessThan(propriedades.timeoutSaude()));
	}

	@Test
	void resultadoCompartilhadoNaoMisturaOQueCadaUmVe() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(jsonPath("$.components.extrator").exists());
		mvc.perform(get("/actuator/health"))
				.andExpect(jsonPath("$.components").doesNotExist());
		assertThat(EXTRATOR.recebidas()).isEqualTo(1);
	}

	@Test
	void extracaoNaoUsaOPrazoCurtoDoHealth() throws IOException {
		assertThat(propriedades.timeoutSaude()).isEqualTo(Duration.ofSeconds(3));
		assertThat(propriedades.timeoutLeitura()).isEqualTo(Duration.ofSeconds(120));

		EXTRATOR.responder(200, new ClassPathResource("contrato/resultado-extracao.exemplo.json")
				.getContentAsString(StandardCharsets.UTF_8));
		EXTRATOR.atrasar(Duration.ofSeconds(4)); // mais que os 3s do health

		var escala = new ArquivoParaExtracao("escala.xlsx", new ByteArrayResource(new byte[] { 1 }));
		assertThat(cliente.extrair(SolicitacaoExtracao.somenteEscala(escala)).getEscala()).isNotNull();
	}

	@Test
	void infoNaoEExposto() throws Exception {
		mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/info").header(HttpHeaders.AUTHORIZATION, bearer(Perfil.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
	}
}
