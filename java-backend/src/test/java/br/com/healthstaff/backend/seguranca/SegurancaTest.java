package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Login, validação do JWT e regras de acesso por perfil, passando pelos filtros reais. */
@SpringBootTest
@AutoConfigureMockMvc
class SegurancaTest extends IntegracaoComBanco {

	static final String FRONTEND = "http://localhost:3000";

	@Autowired
	MockMvc mvc;

	@Autowired
	UsuarioRepository usuarios;

	@Autowired
	TokenService tokens;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void limpar() {
		jdbc.update("delete from usuario");
	}

	Usuario usuario(Perfil perfil) {
		return UsuariosDeTeste.obter(usuarios, perfil.name().toLowerCase() + "@teste.local", perfil);
	}

	String bearer(Usuario usuario) {
		return "Bearer " + tokens.emitir(usuario).token();
	}

	static MockHttpServletRequestBuilder login(String email, String senha) {
		return post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}");
	}

	@Nested
	class Login {

		@Test
		void credenciaisCorretasDevolvemTokenEUsuarioSemHash() throws Exception {
			usuario(Perfil.GESTOR_RH);

			// E-mail com espaços e maiúsculas: é normalizado antes da busca.
			mvc.perform(login("  Gestor_RH@Teste.Local ", UsuariosDeTeste.SENHA))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.tipo").value("Bearer"))
					.andExpect(jsonPath("$.token", not(emptyString())))
					.andExpect(jsonPath("$.expiraEm").exists())
					.andExpect(jsonPath("$.usuario.email").value("gestor_rh@teste.local"))
					.andExpect(jsonPath("$.usuario.perfil").value("GESTOR_RH"))
					.andExpect(jsonPath("$.usuario.senhaHash").doesNotExist())
					.andExpect(jsonPath("$.usuario.password").doesNotExist());
		}

		@Test
		void tokenDoLoginDaAcessoAApi() throws Exception {
			usuario(Perfil.AUDITOR);
			var corpo = mvc.perform(login("auditor@teste.local", UsuariosDeTeste.SENHA))
					.andReturn().getResponse().getContentAsString();
			var token = corpo.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

			mvc.perform(get("/api/v1/auth/eu").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.email").value("auditor@teste.local"))
					.andExpect(jsonPath("$.perfil").value("AUDITOR"));
		}

		@Test
		void senhaErradaEmailInexistenteEUsuarioInativoRecebemAMesmaResposta() throws Exception {
			usuario(Perfil.GESTOR_RH);
			var inativo = usuario(Perfil.AUDITOR);
			inativo.desativar();
			usuarios.save(inativo);

			for (var tentativa : new MockHttpServletRequestBuilder[] {
					login("gestor_rh@teste.local", "senha-errada-123"),
					login("ninguem@teste.local", UsuariosDeTeste.SENHA),
					login("auditor@teste.local", UsuariosDeTeste.SENHA) }) {
				mvc.perform(tentativa)
						.andExpect(status().isUnauthorized())
						.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
						.andExpect(jsonPath("$.codigo").value("CREDENCIAIS_INVALIDAS"))
						.andExpect(jsonPath("$.detail").value("e-mail ou senha inválidos"));
			}
		}

		@Test
		void senhaAcimaDoLimiteDoBcryptERecusadaSemErroInterno() throws Exception {
			usuario(Perfil.GESTOR_RH);

			mvc.perform(login("gestor_rh@teste.local", "x".repeat(100)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("CREDENCIAIS_INVALIDAS"));
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = { "{}", "{\"email\":\"nao-e-email\",\"senha\":\"x\"}", "{\"email\":\"a@b.c\"}", "" })
		void corpoInvalidoRecebe400ComCodigo(String corpo) throws Exception {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"));
		}
	}

	@Nested
	class Token {

		@Test
		void semTokenRecebe401ComCodigoEWwwAuthenticate() throws Exception {
			mvc.perform(get("/api/v1/colaboradores"))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = { "Bearer abc.def.ghi", "Bearer ", "Bearer nao-e-jwt" })
		void tokenMalformadoRecebe401(String autorizacao) throws Exception {
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
		}

		@Test
		void tokenExpiradoRecebe401() throws Exception {
			var ontem = new TokenService(new SegurancaProperties.Jwt(SEGREDO_JWT_TESTE, Duration.ofHours(8)),
					Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC));
			var token = ontem.emitir(usuario(Perfil.ADMIN)).token();

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"))
					.andExpect(jsonPath("$.detail").value("sessão expirada ou inválida; faça login novamente"));
		}

		@Test
		void tokenAssinadoComOutraChaveRecebe401() throws Exception {
			var forjador = new TokenService(
					new SegurancaProperties.Jwt("chave-de-quem-quer-forjar-um-token-admin", Duration.ofHours(8)),
					Clock.systemUTC());
			var token = forjador.emitir(usuario(Perfil.ADMIN)).token();

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isUnauthorized());
		}

		@Test
		void usuarioDesativadoPerdeOAcessoNaHora() throws Exception {
			var gestor = usuario(Perfil.GESTOR_RH);
			var autorizacao = bearer(gestor);
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isOk());

			gestor.desativar();
			usuarios.save(gestor);

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
		}

		@Test
		void usuarioRemovidoPerdeOAcesso() throws Exception {
			var autorizacao = bearer(usuario(Perfil.ADMIN));
			jdbc.update("delete from usuario");

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	class Perfis {

		@ParameterizedTest
		@EnumSource(Perfil.class)
		void todosOsPerfisLeem(Perfil perfil) throws Exception {
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, bearer(usuario(perfil))))
					.andExpect(status().isOk());
		}

		@Test
		void auditorNaoImportaNemReprocessa() throws Exception {
			var autorizacao = bearer(usuario(Perfil.AUDITOR));
			var arquivo = new MockMultipartFile("arquivo", "escala.xlsx", "application/octet-stream", new byte[] { 1 });

			mvc.perform(multipart("/api/v1/importacoes/escalas").file(arquivo)
					.header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
			mvc.perform(multipart("/api/v1/importacoes/ponto").file(arquivo)
					.header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isForbidden());
			mvc.perform(post("/api/v1/analises/1999-01").header(HttpHeaders.AUTHORIZATION, autorizacao))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
		}

		@ParameterizedTest
		@EnumSource(value = Perfil.class, names = { "ADMIN", "GESTOR_RH" })
		void adminEGestorReprocessam(Perfil perfil) throws Exception {
			// Passa pela segurança e chega à regra de negócio (mês sem escala).
			mvc.perform(post("/api/v1/analises/1999-01").header(HttpHeaders.AUTHORIZATION, bearer(usuario(perfil))))
					.andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.codigo").value("SEM_ESCALA_VIGENTE"));
		}
	}

	@Nested
	class RotasLivres {

		@Test
		void healthcheckSemToken() throws Exception {
			// 503 aqui é o extrator fora do ar nos testes; o que importa é não pedir login.
			mvc.perform(get("/actuator/health"))
					.andExpect(resultado -> assertThat(resultado.getResponse().getStatus()).isIn(200, 503))
					.andExpect(jsonPath("$.status").exists());
		}

		@Test
		void rotaForaDaApiENegada() throws Exception {
			mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
			mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(usuario(Perfil.ADMIN))))
					.andExpect(status().isForbidden());
		}

		@Test
		void preflightPassaSemToken() throws Exception {
			mvc.perform(options("/api/v1/importacoes/escalas")
					.header(HttpHeaders.ORIGIN, FRONTEND)
					.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
					.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
		}

		@Test
		void respostaDe401LevaCabecalhosCorsParaOFrontendLerOCodigo() throws Exception {
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.ORIGIN, FRONTEND))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
		}
	}
}
