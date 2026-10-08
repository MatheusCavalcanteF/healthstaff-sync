package br.com.healthstaff.backend.colaborador;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.extrator.contrato.ResultadoExtracao;
import br.com.healthstaff.backend.importacao.ImportacaoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/colaboradores} sobre a resposta-exemplo do extrator:
 * 5 colaboradores do Two (90001-90005, "... FICTICIA ...") e, da escala,
 * VANIA (90002, já do Two) e RECEM CONTRATADA (provisória).
 */
@SpringBootTest
@AutoConfigureMockMvc
// Perfil com permissão de escrita (importa e reprocessa); as regras de acesso estão em SegurancaTest.
@WithMockUser(roles = "GESTOR_RH")
class ColaboradorApiTest extends IntegracaoComBanco {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ImportacaoService importacoes;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void importarExemplo() throws IOException {
		jdbc.execute("""
				truncate table inconformidade, consolidacao_horas, analise, plantao, escala, saldo_banco_horas,
				    inconsistencia, importacao, colaborador, setor restart identity cascade""");
		var exemplo = exemplo();
		importacoes.registrar(exemplo.getBancoHoras());
		importacoes.registrar(exemplo.getEscala());
	}

	ResultadoExtracao exemplo() throws IOException {
		return jsonMapper.readValue(new ClassPathResource("contrato/resultado-extracao.exemplo.json")
				.getContentAsString(StandardCharsets.UTF_8), ResultadoExtracao.class);
	}

	ResultActions listar(String... parametros) throws Exception {
		var requisicao = get("/api/v1/colaboradores");
		for (int i = 0; i < parametros.length; i += 2) {
			requisicao.param(parametros[i], parametros[i + 1]);
		}
		return mvc.perform(requisicao);
	}

	@Nested
	class Listagem {

		@Test
		void todosOrdenadosPorNomeComVinculoECategoria() throws Exception {
			listar()
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElementos").value(6))
					.andExpect(jsonPath("$.pagina").value(0))
					.andExpect(jsonPath("$.tamanho").value(50))
					.andExpect(jsonPath("$.totalPaginas").value(1))
					.andExpect(jsonPath("$.conteudo[*].nome").value(contains(
							"RECEM CONTRATADA", "TEREZA FICTICIA ALVES", "VANIA FICTICIA ROCHA",
							"WILMA FICTICIA PRADO", "YARA FICTICIA BRAGA", "ZILDA FICTICIA LIMA")));
		}

		@Test
		void colaboradorOficialComDadosDaEscalaESaldoDoTwo() throws Exception {
			listar("busca", "vania")
					.andExpect(jsonPath("$.conteudo", hasSize(1)))
					.andExpect(jsonPath("$.conteudo[0].matricula").value("90002"))
					.andExpect(jsonPath("$.conteudo[0].vinculo").value("OFICIAL"))
					.andExpect(jsonPath("$.conteudo[0].cargo").value("Enfª Folguista Diurna"))
					.andExpect(jsonPath("$.conteudo[0].categoria").value("ENFERMEIRO"))
					.andExpect(jsonPath("$.conteudo[0].jornada").value("12x36"))
					.andExpect(jsonPath("$.conteudo[0].registroConselho").value("100007"))
					.andExpect(jsonPath("$.conteudo[0].saldoBancoHoras.minutos").value(982))
					.andExpect(jsonPath("$.conteudo[0].saldoBancoHoras.dataReferencia").value("2026-10-01"));
		}

		@Test
		void colaboradorProvisorioSemMatriculaNemSaldo() throws Exception {
			listar("vinculo", "PROVISORIO")
					.andExpect(jsonPath("$.totalElementos").value(1))
					.andExpect(jsonPath("$.conteudo[0].nome").value("RECEM CONTRATADA"))
					.andExpect(jsonPath("$.conteudo[0].vinculo").value("PROVISORIO"))
					.andExpect(jsonPath("$.conteudo[0].matricula").doesNotExist())
					.andExpect(jsonPath("$.conteudo[0].saldoBancoHoras").doesNotExist())
					.andExpect(jsonPath("$.conteudo[0].categoria").value("TECNICO_ENFERMAGEM"));
		}

		@Test
		void soNoTwoSemCargoNaoTemCategoriaInventada() throws Exception {
			// O relatório do Two não traz cargo: a categoria fica desconhecida, não "OUTRO".
			listar("busca", "90001")
					.andExpect(jsonPath("$.conteudo[0].vinculo").value("OFICIAL"))
					.andExpect(jsonPath("$.conteudo[0].cargo").doesNotExist())
					.andExpect(jsonPath("$.conteudo[0].categoria").doesNotExist());
		}

		@Test
		void somenteOficiais() throws Exception {
			listar("vinculo", "OFICIAL")
					.andExpect(jsonPath("$.totalElementos").value(5))
					.andExpect(jsonPath("$.conteudo[*].vinculo").value(everyItem(org.hamcrest.Matchers.is("OFICIAL"))));
		}

		@Test
		void saldoMaisRecenteDoTwo() throws Exception {
			// Relatório seguinte do Two (15/10): a listagem mostra o saldo mais recente.
			var novo = exemplo().getBancoHoras();
			novo.setIdLote(java.util.UUID.randomUUID());
			novo.getOrigem().setHashSha256("c".repeat(64));
			novo.setPeriodoInicio(java.time.LocalDate.of(2026, 10, 15));
			novo.setPeriodoFim(java.time.LocalDate.of(2026, 10, 15));
			novo.getSetores().forEach(setor -> setor.getColaboradores().forEach(c -> c.getSaldoBancoHoras()
					.minutos(-30).dataReferencia(java.time.LocalDate.of(2026, 10, 15))));
			importacoes.registrar(novo);

			listar("busca", "90002")
					.andExpect(jsonPath("$.conteudo[0].saldoBancoHoras.minutos").value(-30))
					.andExpect(jsonPath("$.conteudo[0].saldoBancoHoras.dataReferencia").value("2026-10-15"));
		}
	}

	@Nested
	class Busca {

		@ParameterizedTest(name = "busca \"{0}\" -> {1} resultado(s)")
		@CsvSource({
				"vânia, 1", // acento e minúsculas indiferentes
				"FICTICIA, 5",
				"fictícia rocha, 1",
				"  recem   contratada , 1", // espaços extras
				"9000, 5", // início da matrícula
				"90004, 1",
				"0004, 0", // matrícula só pelo início
				"%, 0", // curinga do LIKE é texto, não padrão
				"_, 0",
				"inexistente, 0",
		})
		void porNomeOuMatricula(String busca, int esperados) throws Exception {
			listar("busca", busca)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElementos").value(esperados));
		}

		@Test
		void buscaEFiltroDeVinculoCombinados() throws Exception {
			listar("busca", "contratada", "vinculo", "OFICIAL").andExpect(jsonPath("$.totalElementos").value(0));
			listar("busca", "contratada", "vinculo", "PROVISORIO").andExpect(jsonPath("$.totalElementos").value(1));
		}
	}

	@Nested
	class Paginacao {

		@Test
		void segundaPagina() throws Exception {
			listar("tamanho", "2", "pagina", "1")
					.andExpect(jsonPath("$.conteudo", hasSize(2)))
					.andExpect(jsonPath("$.pagina").value(1))
					.andExpect(jsonPath("$.tamanho").value(2))
					.andExpect(jsonPath("$.totalElementos").value(6))
					.andExpect(jsonPath("$.totalPaginas").value(3))
					.andExpect(jsonPath("$.conteudo[0].nome").value("VANIA FICTICIA ROCHA"));
		}

		@Test
		void paginaAlemDoFim() throws Exception {
			listar("pagina", "9").andExpect(status().isOk()).andExpect(jsonPath("$.conteudo", hasSize(0)));
		}

		@ParameterizedTest(name = "{0}={1}")
		@CsvSource({ "tamanho, 0", "tamanho, 201", "pagina, -1", "vinculo, TEMPORARIO", "tamanho, abc" })
		void parametrosInvalidos(String parametro, String valor) throws Exception {
			listar(parametro, valor)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.codigo").value("PARAMETRO_INVALIDO"))
					.andExpect(jsonPath("$.detail").value(not(emptyOrNullString())));
		}
	}

	@Test
	void cadastroVazio() throws Exception {
		jdbc.execute("truncate table colaborador restart identity cascade");

		listar().andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElementos").value(0))
				.andExpect(jsonPath("$.conteudo", hasSize(0)));
	}
}
