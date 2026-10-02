package br.com.healthstaff.backend.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.extrator.ExtratorFalso;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoints de importação de ponta a ponta no backend: upload -> extrator
 * (servidor HTTP falso que devolve a resposta-exemplo da API Python) ->
 * Postgres 18 embutido.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportacaoControllerTest extends IntegracaoComBanco {

	static final ExtratorFalso EXTRATOR = new ExtratorFalso();
	static final String EXEMPLO = ler("contrato/resultado-extracao.exemplo.json");
	static final MediaType PROBLEMA = MediaType.APPLICATION_PROBLEM_JSON;

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
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void preparar() {
		jdbc.execute("""
				truncate table plantao, escala, saldo_banco_horas, inconsistencia, importacao, colaborador, setor
				restart identity cascade""");
		EXTRATOR.reiniciar();
		EXTRATOR.responder(200, EXEMPLO);
	}

	static String ler(String recurso) {
		try {
			return new ClassPathResource(recurso).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static MockMultipartFile arquivo(String nome) {
		return new MockMultipartFile("arquivo", nome, "application/octet-stream",
				("conteudo de " + nome).getBytes(StandardCharsets.UTF_8));
	}

	ResultActions enviarEscala() throws Exception {
		return mvc.perform(multipart("/api/v1/importacoes/escalas").file(arquivo("ESCALA OUTUBRO 2026.xlsx")));
	}

	ResultActions enviarPonto(String... dataReferencia) throws Exception {
		var requisicao = multipart("/api/v1/importacoes/ponto").file(arquivo("TWO - Banco de Horas.xlsx"));
		if (dataReferencia.length > 0) {
			requisicao.param("dataReferencia", dataReferencia[0]);
		}
		return mvc.perform(requisicao);
	}

	int contar(String tabela) {
		return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
	}

	@Nested
	class Escalas {

		@Test
		void escalaNovaCriaImportacaoEVersao() throws Exception {
			enviarEscala()
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", containsString("/api/v1/importacoes/")))
					.andExpect(jsonPath("$.tipoDocumento").value("ESCALA_EXCEL"))
					.andExpect(jsonPath("$.duplicada").value(false))
					.andExpect(jsonPath("$.periodoInicio").value("2026-10-01"))
					.andExpect(jsonPath("$.escalas", hasSize(1)))
					.andExpect(jsonPath("$.escalas[0].setor").value("UTI PEDIÁTRICA"))
					.andExpect(jsonPath("$.escalas[0].versao").value(1))
					.andExpect(jsonPath("$.escalas[0].vigente").value(true))
					.andExpect(jsonPath("$.saldos").value(0))
					.andExpect(jsonPath("$.inconsistencias", hasSize(3)))
					.andExpect(jsonPath("$.inconsistencias[?(@.codigo == 'COLABORADOR_NAO_RECONHECIDO')].nomeColaborador")
							.value("RECEM CONTRATADA"));

			assertThat(contar("importacao")).isEqualTo(1);
			assertThat(contar("plantao")).isPositive();
			// A resposta do extrator também traz um banco de horas: o endpoint de escala o ignora.
			assertThat(contar("saldo_banco_horas")).isZero();
		}

		@Test
		void enviaAoExtratorOArquivoComoEscala() throws Exception {
			enviarEscala().andExpect(status().isCreated());

			var requisicao = EXTRATOR.ultima();
			assertThat(requisicao.caminho()).isEqualTo("/extracoes");
			assertThat(requisicao.corpoTexto())
					.contains("name=\"escala\"; filename=\"ESCALA OUTUBRO 2026.xlsx\"")
					.contains("conteudo de ESCALA OUTUBRO 2026.xlsx")
					.doesNotContain("name=\"banco_horas\"")
					// Banco vazio: não há cadastro a enviar.
					.doesNotContain("name=\"cadastro\"");
		}

		@Test
		void enviaOCadastroConhecidoParaOExtratorAssociarNomes() throws Exception {
			enviarPonto("2026-10-01").andExpect(status().isCreated());

			enviarEscala().andExpect(status().isCreated());

			var corpo = EXTRATOR.ultima().corpoTexto();
			assertThat(corpo).contains("name=\"cadastro\"");
			var json = corpo.substring(corpo.indexOf('[', corpo.indexOf("name=\"cadastro\"")));
			var cadastro = jsonMapper.readTree(json.substring(0, json.indexOf(']') + 1));
			assertThat(cadastro).hasSize(5);
			assertThat(cadastro.get(0).get("matricula").asString()).isEqualTo("90001");
			assertThat(cadastro.get(0).get("nome").asString()).isEqualTo("TEREZA FICTICIA ALVES");
		}

		@Test
		void mesmoArquivoDuasVezesDevolveAImportacaoOriginal() throws Exception {
			var primeira = enviarEscala().andExpect(status().isCreated()).andReturn();
			var id = jsonMapper.readTree(primeira.getResponse().getContentAsString()).get("id").asString();

			enviarEscala()
					.andExpect(status().isOk())
					.andExpect(header().doesNotExist("Location"))
					.andExpect(jsonPath("$.duplicada").value(true))
					.andExpect(jsonPath("$.id").value(id))
					.andExpect(jsonPath("$.escalas[0].versao").value(1));

			assertThat(contar("importacao")).isEqualTo(1);
			assertThat(contar("escala")).isEqualTo(1);
		}
	}

	@Nested
	class Ponto {

		@Test
		void bancoDeHorasComDataDeReferencia() throws Exception {
			enviarPonto("2026-10-01")
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.tipoDocumento").value("PONTO_EXCEL"))
					.andExpect(jsonPath("$.saldos").value(5))
					.andExpect(jsonPath("$.escalas", hasSize(0)));

			var corpo = EXTRATOR.ultima().corpoTexto();
			assertThat(corpo).contains("name=\"banco_horas\"; filename=\"TWO - Banco de Horas.xlsx\"")
					.contains("name=\"data_referencia_ponto\"")
					.contains("2026-10-01")
					.doesNotContain("name=\"escala\"");
			assertThat(contar("saldo_banco_horas")).isEqualTo(5);
			assertThat(contar("plantao")).isZero();
		}

		@Test
		void semDataDeReferenciaODefaultFicaComOExtrator() throws Exception {
			enviarPonto().andExpect(status().isCreated());

			assertThat(EXTRATOR.ultima().corpoTexto()).doesNotContain("data_referencia_ponto");
		}

		@Test
		void dataDeReferenciaInvalida() throws Exception {
			enviarPonto("31/10/2026")
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(PROBLEMA))
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"))
					.andExpect(jsonPath("$.detail").value(containsString("AAAA-MM-DD")));

			assertThat(EXTRATOR.recebidas()).isZero();
		}
	}

	@Nested
	class Consulta {

		@Test
		void detalhaPeloLocation() throws Exception {
			var criada = enviarEscala().andExpect(status().isCreated()).andReturn();
			var local = criada.getResponse().getHeader("Location");

			var id = jsonMapper.readTree(criada.getResponse().getContentAsString()).get("id").asString();
			assertThat(local).endsWith("/api/v1/importacoes/" + id);

			mvc.perform(get(local))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(id))
					.andExpect(jsonPath("$.tipoDocumento").value("ESCALA_EXCEL"))
					.andExpect(jsonPath("$.duplicada").value(false))
					.andExpect(jsonPath("$.inconsistencias", hasSize(3)));
		}

		@Test
		void importacaoInexistente() throws Exception {
			mvc.perform(get("/api/v1/importacoes/00000000-0000-0000-0000-000000000000"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.codigo").value("IMPORTACAO_NAO_ENCONTRADA"));
		}
	}

	@Nested
	class Erros {

		@Test
		void arquivoRecusadoPeloExtrator() throws Exception {
			EXTRATOR.responder(422, """
					{"codigo": "TIPO_INCOMPATIVEL",
					 "mensagem": "o arquivo foi reconhecido como PONTO_EXCEL, não como escala",
					 "campo": "escala", "arquivo": "ESCALA OUTUBRO 2026.xlsx"}""");

			enviarEscala()
					.andExpect(status().isUnprocessableContent())
					.andExpect(content().contentTypeCompatibleWith(PROBLEMA))
					.andExpect(jsonPath("$.codigo").value("TIPO_INCOMPATIVEL"))
					.andExpect(jsonPath("$.title").value("Arquivo recusado"))
					.andExpect(jsonPath("$.detail").value(containsString("PONTO_EXCEL")))
					.andExpect(jsonPath("$.arquivo").value("ESCALA OUTUBRO 2026.xlsx"));

			assertThat(contar("importacao")).isZero();
		}

		@Test
		void extratorForaDoArViraServicoIndisponivelSemDetalheInterno() throws Exception {
			EXTRATOR.responder(500, "Internal Server Error", "text/plain");

			enviarEscala()
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.codigo").value("EXTRATOR_INDISPONIVEL"))
					.andExpect(jsonPath("$.detail").value(not(containsString("500"))))
					.andExpect(jsonPath("$.detail").value(not(containsString("127.0.0.1"))));

			assertThat(contar("importacao")).isZero();
		}

		@Test
		void extratorSemOLoteEsperado() throws Exception {
			EXTRATOR.responder(200, "{\"escala\": null, \"bancoHoras\": null}");

			enviarEscala()
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.codigo").value("EXTRATOR_INDISPONIVEL"));
		}

		@Test
		void semArquivo() throws Exception {
			mvc.perform(multipart("/api/v1/importacoes/escalas"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("ARQUIVO_AUSENTE"))
					.andExpect(jsonPath("$.detail").value(containsString("arquivo")));
		}

		@Test
		void arquivoVazioNemChegaAoExtrator() throws Exception {
			var vazio = new MockMultipartFile("arquivo", "escala.xlsx", "application/octet-stream", new byte[0]);

			mvc.perform(multipart("/api/v1/importacoes/escalas").file(vazio))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("ARQUIVO_INVALIDO"));

			assertThat(EXTRATOR.recebidas()).isZero();
		}

		@Test
		void arquivoSemNome() throws Exception {
			var semNome = new MockMultipartFile("arquivo", "", "application/octet-stream", new byte[] { 1 });

			mvc.perform(multipart("/api/v1/importacoes/escalas").file(semNome))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("ARQUIVO_INVALIDO"));

			assertThat(EXTRATOR.recebidas()).isZero();
		}
	}
}
