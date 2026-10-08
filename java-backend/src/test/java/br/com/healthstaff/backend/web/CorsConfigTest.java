package br.com.healthstaff.backend.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** CORS para o frontend Next.js (http://localhost:3000 por padrão). */
@SpringBootTest
@AutoConfigureMockMvc
// Perfil com permissão de escrita (importa e reprocessa); as regras de acesso estão em SegurancaTest.
@WithMockUser(roles = "GESTOR_RH")
class CorsConfigTest extends IntegracaoComBanco {

	static final String FRONTEND = "http://localhost:3000";

	@Autowired
	MockMvc mvc;

	@ParameterizedTest(name = "preflight {0}")
	@ValueSource(strings = { "GET", "POST", "PUT", "DELETE", "PATCH" })
	void preflightDoFrontendELiberado(String metodo) throws Exception {
		mvc.perform(options("/api/v1/importacoes/escalas")
				.header(HttpHeaders.ORIGIN, FRONTEND)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, metodo)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type, authorization"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(metodo)))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
	}

	@Test
	void outraOrigemERecusada() throws Exception {
		mvc.perform(options("/api/v1/analises/2026-10")
				.header(HttpHeaders.ORIGIN, "http://site-malicioso.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void respostaDeErroTambemLevaCabecalhosCors() throws Exception {
		// O frontend precisa ler o ProblemDetail (ex.: ANALISE_NAO_ENCONTRADA) mesmo em 404.
		mvc.perform(get("/api/v1/analises/2025-01").header(HttpHeaders.ORIGIN, FRONTEND))
				.andExpect(status().isNotFound())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")));
	}

	@Test
	void foraDaApiNaoHaCors() throws Exception {
		mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, FRONTEND))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void semCabecalhoOriginNaoMudaNada() throws Exception {
		// Chamadas servidor-a-servidor (ex.: renderização no servidor do Next) não usam CORS.
		mvc.perform(get("/api/v1/colaboradores"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
