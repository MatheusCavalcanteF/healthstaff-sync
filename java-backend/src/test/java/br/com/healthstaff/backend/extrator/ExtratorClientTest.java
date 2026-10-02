package br.com.healthstaff.backend.extrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.extrator.contrato.CodigoInconsistencia;
import br.com.healthstaff.backend.extrator.contrato.Colaborador;
import br.com.healthstaff.backend.extrator.contrato.Inconsistencia;
import br.com.healthstaff.backend.extrator.contrato.ResultadoExtracao;
import br.com.healthstaff.backend.extrator.contrato.Severidade;
import br.com.healthstaff.backend.extrator.contrato.TipoDocumento;
import br.com.healthstaff.backend.extrator.contrato.TipoMarcacao;
import br.com.healthstaff.backend.extrator.contrato.Turno;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente do extrator contra um servidor HTTP real que imita o python-extractor.
 * A resposta de sucesso é uma resposta real da API Python (dados fictícios),
 * validada também pelos testes do python-extractor.
 */
@SpringBootTest(properties = {
		"healthstaff.extrator.api-key=chave-de-teste",
		"healthstaff.extrator.timeout-leitura=2s" })
class ExtratorClientTest extends IntegracaoComBanco {

	static final ExtratorFalso EXTRATOR = new ExtratorFalso();
	static final String EXEMPLO = lerExemplo();

	@DynamicPropertySource
	static void apontarParaExtratorFalso(DynamicPropertyRegistry registro) {
		registro.add("healthstaff.extrator.url", EXTRATOR::url);
	}

	@AfterAll
	static void encerrar() {
		EXTRATOR.close();
	}

	@Autowired
	ExtratorClient cliente;

	@Autowired
	ExtratorHealthIndicator saude;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void reiniciar() {
		EXTRATOR.reiniciar();
	}

	static String lerExemplo() {
		try {
			return new ClassPathResource("contrato/resultado-extracao.exemplo.json")
					.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static ArquivoParaExtracao arquivo(String nome, String conteudo) {
		return new ArquivoParaExtracao(nome, new ByteArrayResource(conteudo.getBytes(StandardCharsets.UTF_8)));
	}

	static SolicitacaoExtracao escalaEBancoHoras() {
		return new SolicitacaoExtracao(
				arquivo("ESCALA OUTUBRO 2026.xlsx", "conteudo-da-escala"),
				arquivo("bh.csv", "conteudo-do-banco"),
				LocalDate.of(2026, 10, 1));
	}

	static Colaborador colaborador(ResultadoExtracao resultado, String nome) {
		return resultado.getEscala().getSetores().stream()
				.flatMap(setor -> setor.getColaboradores().stream())
				.filter(c -> c.getNome().equals(nome))
				.findFirst()
				.orElseThrow();
	}

	@Nested
	class Sucesso {

		@Test
		void desserializaRespostaRealDoExtrator() {
			EXTRATOR.responder(200, EXEMPLO);

			var resultado = cliente.extrair(escalaEBancoHoras());

			var escala = resultado.getEscala();
			assertThat(escala.getOrigem().getTipoDocumento()).isEqualTo(TipoDocumento.ESCALA_EXCEL);
			assertThat(escala.getPeriodoInicio()).isEqualTo(LocalDate.of(2026, 10, 1));
			assertThat(escala.getTotalColaboradores()).isEqualTo(2);
			assertThat(escala.getGeradoEm()).isNotNull();

			var vania = colaborador(resultado, "VANIA FICTICIA ROCHA");
			assertThat(vania.getMatricula()).isEqualTo("90002");
			assertThat(vania.getFolguista()).isTrue();
			var primeiro = vania.getPlantoes().getFirst();
			assertThat(primeiro.getTipo()).isEqualTo(TipoMarcacao.PLANTAO);
			assertThat(primeiro.getTurno()).isEqualTo(Turno.NOTURNO_12H);
			assertThat(primeiro.getInicio()).isEqualTo(LocalDateTime.of(2026, 10, 1, 19, 0));
			assertThat(primeiro.getFim()).isEqualTo(LocalDateTime.of(2026, 10, 2, 7, 0));
			assertThat(primeiro.getDuracaoHoras()).isEqualByComparingTo("12");

			// Quem não está no Two vem sem matrícula e com aviso, nunca é descartado.
			assertThat(colaborador(resultado, "RECEM CONTRATADA").getMatricula()).isNull();
			assertThat(escala.getInconsistencias())
					.filteredOn(i -> i.getCodigo() == CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO)
					.extracting(Inconsistencia::getNomeColaborador, Inconsistencia::getSeveridade)
					.containsExactly(org.assertj.core.groups.Tuple.tuple("RECEM CONTRATADA", Severidade.AVISO));

			var saldos = resultado.getBancoHoras().getSetores().stream()
					.flatMap(setor -> setor.getColaboradores().stream())
					.filter(c -> "90004".equals(c.getMatricula()))
					.map(Colaborador::getSaldoBancoHoras)
					.toList();
			assertThat(saldos).singleElement().satisfies(saldo -> {
				assertThat(saldo.getMinutos()).isEqualTo(-243);
				assertThat(saldo.getHoras()).isEqualByComparingTo("-4.05");
				assertThat(saldo.getDataReferencia()).isEqualTo(LocalDate.of(2026, 10, 1));
			});
		}

		@Test
		void enviaMultipartNoFormatoEsperadoPeloExtrator() {
			EXTRATOR.responder(200, EXEMPLO);

			cliente.extrair(escalaEBancoHoras());

			var requisicao = EXTRATOR.ultima();
			assertThat(requisicao.metodo()).isEqualTo("POST");
			assertThat(requisicao.caminho()).isEqualTo("/extracoes");
			assertThat(requisicao.cabecalho("Content-Type")).startsWith("multipart/form-data");
			assertThat(requisicao.cabecalho("X-API-Key")).isEqualTo("chave-de-teste");
			assertThat(requisicao.corpoTexto())
					.contains("name=\"escala\"; filename=\"ESCALA OUTUBRO 2026.xlsx\"")
					.contains("conteudo-da-escala")
					.contains("name=\"banco_horas\"; filename=\"bh.csv\"")
					.contains("conteudo-do-banco")
					.contains("name=\"data_referencia_ponto\"")
					.contains("2026-10-01");
		}

		@Test
		void enviaCadastroConhecidoComoJson() throws Exception {
			EXTRATOR.responder(200, EXEMPLO);

			cliente.extrair(SolicitacaoExtracao.somenteEscala(arquivo("escala.xlsx", "x"),
					java.util.List.of(new PessoaConhecida("90002", "VANIA FICTICIA ROCHA"),
							new PessoaConhecida("90004", "YARA FICTICIA BRAGA"))));

			var corpo = EXTRATOR.ultima().corpoTexto();
			assertThat(corpo).contains("name=\"cadastro\"");
			var json = corpo.substring(corpo.indexOf('[', corpo.indexOf("name=\"cadastro\"")));
			json = json.substring(0, json.indexOf(']') + 1);
			assertThat(jsonMapper.readTree(json).toString())
					.isEqualTo("[{\"matricula\":\"90002\",\"nome\":\"VANIA FICTICIA ROCHA\"},"
							+ "{\"matricula\":\"90004\",\"nome\":\"YARA FICTICIA BRAGA\"}]");
		}

		@Test
		void naoEnviaCamposNaoInformados() {
			EXTRATOR.responder(200, EXEMPLO);

			cliente.extrair(SolicitacaoExtracao.somenteEscala(arquivo("escala.pdf", "pdf")));

			assertThat(EXTRATOR.ultima().corpoTexto())
					.contains("name=\"escala\"")
					.doesNotContain("banco_horas")
					.doesNotContain("data_referencia_ponto")
					.doesNotContain("cadastro");
		}

		@Test
		void toleraCodigoDeInconsistenciaECampoNovos() {
			// Evolução compatível do Python não pode derrubar a importação no Java.
			var futuro = EXEMPLO
					.replace("\"CODIGO_TURNO_DESCONHECIDO\"", "\"CODIGO_CRIADO_NO_FUTURO\"")
					.replaceFirst("\\{", "{\"campoCriadoNoFuturo\": 1,");
			EXTRATOR.responder(200, futuro);

			var resultado = cliente.extrair(escalaEBancoHoras());

			assertThat(resultado.getEscala().getInconsistencias())
					.extracting(Inconsistencia::getCodigo)
					.contains(CodigoInconsistencia.unknown_default_open_api);
		}
	}

	@Nested
	class ArquivoRecusado {

		@Test
		void erroDeConteudoViraExtracaoRejeitada() {
			EXTRATOR.responder(422, """
					{"codigo": "TIPO_INCOMPATIVEL",
					 "mensagem": "o arquivo foi reconhecido como ESCALA_EXCEL, não como banco_horas",
					 "campo": "banco_horas", "arquivo": "escala.xlsx"}""");

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOfSatisfying(ExtracaoRejeitadaException.class, e -> {
						assertThat(e.getStatus()).isEqualTo(422);
						assertThat(e.getErro().getCodigo()).isEqualTo("TIPO_INCOMPATIVEL");
						assertThat(e.getErro().getCampo()).isEqualTo("banco_horas");
						assertThat(e.getErro().getArquivo()).isEqualTo("escala.xlsx");
					});
		}

		@Test
		void arquivoGrandeDemais() {
			EXTRATOR.responder(413, """
					{"codigo": "ARQUIVO_MUITO_GRANDE", "mensagem": "arquivo excede o limite de 20 MB",
					 "campo": "escala", "arquivo": "escala.pdf"}""");

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOfSatisfying(ExtracaoRejeitadaException.class,
							e -> assertThat(e.getStatus()).isEqualTo(413));
		}
	}

	@Nested
	class FalhaDeInfraestrutura {

		@Test
		void credencialRecusada() {
			EXTRATOR.responder(401, """
					{"codigo": "NAO_AUTORIZADO", "mensagem": "cabeçalho X-API-Key ausente ou inválido"}""");

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("api-key");
		}

		@Test
		void erroInterno() {
			EXTRATOR.responder(500, "Internal Server Error", "text/plain");

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("500");
		}

		@Test
		void respostaDeErroForaDoContrato() {
			EXTRATOR.responder(404, "<html>Not Found</html>", "text/html");

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("404");
		}

		@Test
		void respostaDeSucessoForaDoContrato() {
			EXTRATOR.responder(200, EXEMPLO.replace("\"periodoInicio\": \"2026-10-01\"", "\"periodoInicio\": \"ontem\""));

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("fora do contrato");
		}

		@Test
		void versaoDeContratoIncompativel() {
			EXTRATOR.responder(200, EXEMPLO.replace("\"versaoContrato\": \"1.0\"", "\"versaoContrato\": \"2.0\""));

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("versão de contrato");
		}

		@Test
		void timeoutDeLeitura() {
			EXTRATOR.responder(200, EXEMPLO);
			EXTRATOR.atrasar(Duration.ofSeconds(4)); // timeout-leitura do teste: 2s

			assertThatThrownBy(() -> cliente.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("inacessível");
		}

		@Test
		void extratorForaDoAr() throws IOException {
			int portaLivre;
			try (var socket = new ServerSocket(0)) {
				portaLivre = socket.getLocalPort();
			}
			var desligado = new ExtratorClient(
					RestClient.builder().baseUrl("http://127.0.0.1:" + portaLivre).build(), jsonMapper);

			assertThatThrownBy(() -> desligado.extrair(escalaEBancoHoras()))
					.isInstanceOf(ExtratorIndisponivelException.class)
					.hasMessageContaining("inacessível");
		}
	}

	@Nested
	class Saude {

		@Test
		void extratorNoAr() {
			EXTRATOR.responder(200, "{\"status\": \"ok\", \"versao\": \"1.0.0\"}");

			var health = saude.health();

			assertThat(health.getStatus()).isEqualTo(Status.UP);
			assertThat(health.getDetails()).containsEntry("versao", "1.0.0");
			assertThat(EXTRATOR.ultima().caminho()).isEqualTo("/health");
		}

		@Test
		void extratorComProblema() {
			EXTRATOR.responder(503, "indisponível", "text/plain");

			var health = saude.health();

			assertThat(health.getStatus()).isEqualTo(Status.DOWN);
			assertThat(health.getDetails()).containsKey("erro");
		}
	}
}
