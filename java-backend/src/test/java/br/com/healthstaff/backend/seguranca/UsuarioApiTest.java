package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Gestão de usuários pela API, com tokens reais (passa pelos filtros de segurança). */
@SpringBootTest
@AutoConfigureMockMvc
class UsuarioApiTest extends IntegracaoComBanco {

	@Autowired
	MockMvc mvc;

	@Autowired
	UsuarioRepository usuarios;

	@Autowired
	TokenService tokens;

	@Autowired
	JdbcTemplate jdbc;

	Usuario admin;

	@BeforeEach
	void preparar() {
		jdbc.update("delete from usuario");
		admin = UsuariosDeTeste.obter(usuarios, "admin@teste.local", Perfil.ADMIN);
	}

	String bearer(Usuario usuario) {
		return "Bearer " + tokens.emitir(usuario).token();
	}

	ResultActions comoAdmin(MockHttpServletRequestBuilder requisicao) throws Exception {
		return mvc.perform(requisicao.header(HttpHeaders.AUTHORIZATION, bearer(admin)));
	}

	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder requisicao, String corpo) {
		return requisicao.contentType(MediaType.APPLICATION_JSON).content(corpo);
	}

	static String novo(String nome, String email, String senha, String perfil) {
		return """
				{"nome":"%s","email":"%s","senha":"%s","perfil":"%s"}""".formatted(nome, email, senha, perfil);
	}

	static String edicao(String nome, String email, String perfil, String senha) {
		return senha == null
				? """
						{"nome":"%s","email":"%s","perfil":"%s"}""".formatted(nome, email, perfil)
				: """
						{"nome":"%s","email":"%s","perfil":"%s","senha":"%s"}""".formatted(nome, email, perfil, senha);
	}

	ResultActions login(String email, String senha) throws Exception {
		return mvc.perform(json(post("/api/v1/auth/login"), """
				{"email":"%s","senha":"%s"}""".formatted(email, senha)));
	}

	@Nested
	class Acesso {

		static Stream<Arguments> rotas() {
			var id = UUID.randomUUID();
			return Stream.of(
					Arguments.of("GET lista", get("/api/v1/usuarios")),
					Arguments.of("GET um", get("/api/v1/usuarios/" + id)),
					Arguments.of("POST", json(post("/api/v1/usuarios"), novo("X", "x@teste.local", "senha-valida-123", "AUDITOR"))),
					Arguments.of("PUT", json(put("/api/v1/usuarios/" + id), edicao("X", "x@teste.local", "AUDITOR", null))),
					Arguments.of("PATCH status", json(patch("/api/v1/usuarios/" + id + "/status"), "{\"ativo\":false}")));
		}

		@ParameterizedTest(name = "GESTOR_RH: {0}")
		@MethodSource("rotas")
		void gestorRhRecebe403(String rota, MockHttpServletRequestBuilder requisicao) throws Exception {
			var gestor = UsuariosDeTeste.obter(usuarios, "gestor@teste.local", Perfil.GESTOR_RH);
			mvc.perform(requisicao.header(HttpHeaders.AUTHORIZATION, bearer(gestor)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
		}

		@ParameterizedTest(name = "AUDITOR: {0}")
		@MethodSource("rotas")
		void auditorRecebe403(String rota, MockHttpServletRequestBuilder requisicao) throws Exception {
			var auditor = UsuariosDeTeste.obter(usuarios, "auditor@teste.local", Perfil.AUDITOR);
			mvc.perform(requisicao.header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
		}

		@ParameterizedTest(name = "sem token: {0}")
		@MethodSource("rotas")
		void semTokenRecebe401(String rota, MockHttpServletRequestBuilder requisicao) throws Exception {
			mvc.perform(requisicao).andExpect(status().isUnauthorized());
		}

		@Test
		void nadaDoCadastroMudaQuandoONaoAdminTenta() throws Exception {
			var gestor = UsuariosDeTeste.obter(usuarios, "gestor@teste.local", Perfil.GESTOR_RH);
			mvc.perform(json(patch("/api/v1/usuarios/" + admin.getId() + "/status"), "{\"ativo\":false}")
					.header(HttpHeaders.AUTHORIZATION, bearer(gestor)))
					.andExpect(status().isForbidden());

			assertThat(usuarios.findById(admin.getId())).hasValueSatisfying(u -> assertThat(u.isAtivo()).isTrue());
		}
	}

	@Nested
	class Criacao {

		@Test
		void adminCriaUsuarioComSenhaEmBcryptQueJaConsegueEntrar() throws Exception {
			comoAdmin(json(post("/api/v1/usuarios"), novo("Gestora Nova", " Gestora@Teste.Local ", "senha-valida-123", "GESTOR_RH")))
					.andExpect(status().isCreated())
					.andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/usuarios/")))
					.andExpect(jsonPath("$.email").value("gestora@teste.local"))
					.andExpect(jsonPath("$.perfil").value("GESTOR_RH"))
					.andExpect(jsonPath("$.ativo").value(true))
					.andExpect(jsonPath("$.senha").doesNotExist())
					.andExpect(jsonPath("$.senhaHash").doesNotExist());

			var hash = jdbc.queryForObject("select senha_hash from usuario where email = 'gestora@teste.local'", String.class);
			assertThat(hash).startsWith("$2").doesNotContain("senha-valida-123");
			login("gestora@teste.local", "senha-valida-123").andExpect(status().isOk());
		}

		@Test
		void emailJaCadastradoRecebe409SemDiferenciarMaiusculas() throws Exception {
			comoAdmin(json(post("/api/v1/usuarios"), novo("Outro", "ADMIN@teste.local", "senha-valida-123", "AUDITOR")))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.codigo").value("EMAIL_JA_CADASTRADO"));

			assertThat(usuarios.count()).isEqualTo(1);
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
				"{\"nome\":\"X\",\"email\":\"x@teste.local\",\"senha\":\"curta\",\"perfil\":\"AUDITOR\"}",
				"{\"nome\":\"X\",\"email\":\"x@teste.local\",\"senha\":\"senha-valida-123\",\"perfil\":\"ROOT\"}",
				"{\"nome\":\"X\",\"email\":\"nao-e-email\",\"senha\":\"senha-valida-123\",\"perfil\":\"AUDITOR\"}",
				"{\"nome\":\"\",\"email\":\"x@teste.local\",\"senha\":\"senha-valida-123\",\"perfil\":\"AUDITOR\"}",
				"{\"nome\":\"X\",\"email\":\"x@teste.local\",\"senha\":\"senha-valida-123\"}" })
		void dadosInvalidosRecebem400(String corpo) throws Exception {
			comoAdmin(json(post("/api/v1/usuarios"), corpo))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"));
		}

		@Test
		void listaTrazTodosOrdenadosPorNomeSemHash() throws Exception {
			var inativo = UsuariosDeTeste.obter(usuarios, "auditor@teste.local", Perfil.AUDITOR);
			inativo.desativar();
			usuarios.save(inativo);

			comoAdmin(get("/api/v1/usuarios"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2))
					.andExpect(jsonPath("$[0].email").value("admin@teste.local"))
					.andExpect(jsonPath("$[1].ativo").value(false))
					.andExpect(jsonPath("$[0].senhaHash").doesNotExist())
					.andExpect(jsonPath("$[0].password").doesNotExist());
		}
	}

	@Nested
	class Edicao {

		Usuario gestor;

		@BeforeEach
		void gestor() {
			gestor = UsuariosDeTeste.obter(usuarios, "gestor@teste.local", Perfil.GESTOR_RH);
		}

		@Test
		void semSenhaMantemASenhaEAsSessoes() throws Exception {
			var tokenDoGestor = bearer(gestor);

			comoAdmin(json(put("/api/v1/usuarios/" + gestor.getId()), edicao("Gestor Renomeado", "gestor@teste.local", "GESTOR_RH", "")))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nome").value("Gestor Renomeado"));

			login("gestor@teste.local", UsuariosDeTeste.SENHA).andExpect(status().isOk());
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, tokenDoGestor))
					.andExpect(status().isOk());
		}

		@Test
		void novaSenhaValeEDerrubaAsSessoesAbertas() throws Exception {
			var tokenDoGestor = bearer(gestor);

			comoAdmin(json(put("/api/v1/usuarios/" + gestor.getId()), edicao("Gestor", "gestor@teste.local", "GESTOR_RH", "nova-senha-segura-1")))
					.andExpect(status().isOk());

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, tokenDoGestor))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
			login("gestor@teste.local", UsuariosDeTeste.SENHA).andExpect(status().isUnauthorized());
			login("gestor@teste.local", "nova-senha-segura-1").andExpect(status().isOk());
		}

		@Test
		void trocaDePerfilDerrubaAsSessoesEValeNaHora() throws Exception {
			var tokenDoGestor = bearer(gestor);

			comoAdmin(json(put("/api/v1/usuarios/" + gestor.getId()), edicao("Gestor", "gestor@teste.local", "AUDITOR", null)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.perfil").value("AUDITOR"));

			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, tokenDoGestor))
					.andExpect(status().isUnauthorized());
		}

		@Test
		void emailDeOutroUsuarioRecebe409() throws Exception {
			comoAdmin(json(put("/api/v1/usuarios/" + gestor.getId()), edicao("Gestor", "Admin@Teste.local", "GESTOR_RH", null)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.codigo").value("EMAIL_JA_CADASTRADO"));
		}

		@Test
		void senhaNovaFracaRecebe400() throws Exception {
			comoAdmin(json(put("/api/v1/usuarios/" + gestor.getId()), edicao("Gestor", "gestor@teste.local", "GESTOR_RH", "curta")))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail").value(containsString("12 caracteres")));
		}

		@Test
		void usuarioInexistenteRecebe404() throws Exception {
			comoAdmin(json(put("/api/v1/usuarios/" + UUID.randomUUID()), edicao("X", "x@teste.local", "AUDITOR", null)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.codigo").value("USUARIO_NAO_ENCONTRADO"));
		}

		@Test
		void adminNaoTiraOProprioPerfil() throws Exception {
			UsuariosDeTeste.obter(usuarios, "admin2@teste.local", Perfil.ADMIN);

			comoAdmin(json(put("/api/v1/usuarios/" + admin.getId()), edicao("Admin", "admin@teste.local", "AUDITOR", null)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.codigo").value("ALTERACAO_PROPRIA_BLOQUEADA"));
		}

		@Test
		void adminEditaOProprioNome() throws Exception {
			comoAdmin(json(put("/api/v1/usuarios/" + admin.getId()), edicao("Admin Renomeado", "admin@teste.local", "ADMIN", null)))
					.andExpect(status().isOk());
		}
	}

	@Nested
	class Status {

		@Test
		void desativarDerrubaAsSessoesEReativarNaoAsRessuscita() throws Exception {
			var auditor = UsuariosDeTeste.obter(usuarios, "auditor@teste.local", Perfil.AUDITOR);
			var tokenAntigo = bearer(auditor);

			comoAdmin(json(patch("/api/v1/usuarios/" + auditor.getId() + "/status"), "{\"ativo\":false}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.ativo").value(false));
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, tokenAntigo))
					.andExpect(status().isUnauthorized());
			login("auditor@teste.local", UsuariosDeTeste.SENHA).andExpect(status().isUnauthorized());

			comoAdmin(json(patch("/api/v1/usuarios/" + auditor.getId() + "/status"), "{\"ativo\":true}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.ativo").value(true));
			mvc.perform(get("/api/v1/colaboradores").header(HttpHeaders.AUTHORIZATION, tokenAntigo))
					.andExpect(status().isUnauthorized());
			login("auditor@teste.local", UsuariosDeTeste.SENHA).andExpect(status().isOk());
		}

		@Test
		void repetirAMesmaOperacaoNaoMudaNada() throws Exception {
			var auditor = UsuariosDeTeste.obter(usuarios, "auditor@teste.local", Perfil.AUDITOR);
			for (int i = 0; i < 2; i++) {
				comoAdmin(json(patch("/api/v1/usuarios/" + auditor.getId() + "/status"), "{\"ativo\":false}"))
						.andExpect(jsonPath("$.ativo").value(false));
			}
			assertThat(usuarios.findById(auditor.getId()))
					.hasValueSatisfying(u -> assertThat(u.getVersaoCredenciais()).isEqualTo(1));
		}

		@Test
		void adminNaoDesativaAPropriaConta() throws Exception {
			UsuariosDeTeste.obter(usuarios, "admin2@teste.local", Perfil.ADMIN);

			comoAdmin(json(patch("/api/v1/usuarios/" + admin.getId() + "/status"), "{\"ativo\":false}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.codigo").value("ALTERACAO_PROPRIA_BLOQUEADA"));
		}

		@Test
		void adminDesativaOutroAdmin() throws Exception {
			var outro = UsuariosDeTeste.obter(usuarios, "admin2@teste.local", Perfil.ADMIN);

			comoAdmin(json(patch("/api/v1/usuarios/" + outro.getId() + "/status"), "{\"ativo\":false}"))
					.andExpect(status().isOk());
		}

		@Test
		void corpoSemAtivoRecebe400() throws Exception {
			comoAdmin(json(patch("/api/v1/usuarios/" + admin.getId() + "/status"), "{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"));
		}

		@Test
		void idForaDoFormatoRecebe400() throws Exception {
			comoAdmin(get("/api/v1/usuarios/123"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"));
		}

		@Test
		void locationDoCadastroApontaParaUmaRotaQueExiste() throws Exception {
			var local = comoAdmin(json(post("/api/v1/usuarios"), novo("Nova", "nova@teste.local", "senha-valida-123", "AUDITOR")))
					.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

			comoAdmin(get(local)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("nova@teste.local"));
		}
	}
}
