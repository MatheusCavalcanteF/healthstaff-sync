package br.com.healthstaff.backend.extrator;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.healthstaff.backend.extrator.contrato.ErroApi;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao.VersaoContratoEnum;
import br.com.healthstaff.backend.extrator.contrato.ResultadoExtracao;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente do microsserviço de extração ({@code POST /extracoes}).
 *
 * <p>Erros de conteúdo dentro dos arquivos (sigla desconhecida, colaborador
 * fora do Two...) não são exceções: chegam como {@code inconsistencias} nos
 * lotes. Exceções só ocorrem quando o arquivo inteiro é recusado
 * ({@link ExtracaoRejeitadaException}) ou o serviço falha
 * ({@link ExtratorIndisponivelException}).
 */
@Component
public class ExtratorClient {

	private static final Logger log = LoggerFactory.getLogger(ExtratorClient.class);
	private static final ParameterizedTypeReference<Map<String, Object>> MAPA = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public ExtratorClient(RestClient extratorRestClient, JsonMapper jsonMapper) {
		this.restClient = extratorRestClient;
		this.jsonMapper = jsonMapper;
	}

	public ResultadoExtracao extrair(SolicitacaoExtracao solicitacao) {
		MultiValueMap<String, Object> formulario = new LinkedMultiValueMap<>();
		adicionarArquivo(formulario, "escala", solicitacao.escala());
		adicionarArquivo(formulario, "banco_horas", solicitacao.bancoHoras());
		if (solicitacao.dataReferenciaPonto() != null) {
			formulario.add("data_referencia_ponto", solicitacao.dataReferenciaPonto().toString());
		}

		try {
			var resultado = restClient.post()
					.uri("/extracoes")
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.accept(MediaType.APPLICATION_JSON)
					.body(formulario)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (requisicao, resposta) -> tratarErro(resposta))
					.body(ResultadoExtracao.class);
			if (resultado == null) {
				throw new ExtratorIndisponivelException("extrator respondeu sem corpo");
			}
			validarVersaoContrato(resultado.getEscala());
			validarVersaoContrato(resultado.getBancoHoras());
			return resultado;
		}
		catch (ResourceAccessException e) {
			throw new ExtratorIndisponivelException("extrator inacessível: " + e.getMessage(), e);
		}
		catch (RestClientException e) {
			// Ex.: JSON de sucesso que não bate com as classes geradas do contrato.
			throw new ExtratorIndisponivelException("resposta do extrator fora do contrato: " + e.getMessage(), e);
		}
	}

	/** Consulta {@code GET /health}; usado pelo indicador do Actuator. */
	public Map<String, Object> saude() {
		try {
			var corpo = restClient.get().uri("/health").retrieve().body(MAPA);
			return corpo != null ? corpo : Map.of();
		}
		catch (RestClientException e) {
			throw new ExtratorIndisponivelException("extrator inacessível: " + e.getMessage(), e);
		}
	}

	/**
	 * Enums desconhecidos viram {@code unknown_default_open_api} para tolerar códigos
	 * novos de inconsistência; já uma versão de contrato diferente da usada na
	 * geração das classes não pode ser aceita em silêncio.
	 */
	private static void validarVersaoContrato(LoteImportacao lote) {
		if (lote != null && lote.getVersaoContrato() != VersaoContratoEnum._1_0) {
			throw new ExtratorIndisponivelException(
					"versão de contrato do extrator incompatível: " + lote.getVersaoContrato());
		}
	}

	/** Parte de arquivo com o nome original no Content-Disposition (o extrator usa a extensão). */
	private static void adicionarArquivo(MultiValueMap<String, Object> formulario, String campo,
			ArquivoParaExtracao arquivo) {
		if (arquivo != null) {
			var cabecalhos = new HttpHeaders();
			cabecalhos.setContentDispositionFormData(campo, arquivo.nome());
			cabecalhos.setContentType(MediaType.APPLICATION_OCTET_STREAM);
			formulario.add(campo, new HttpEntity<>(arquivo.conteudo(), cabecalhos));
		}
	}

	private void tratarErro(ClientHttpResponse resposta) throws IOException {
		var status = resposta.getStatusCode();
		if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
			throw new ExtratorIndisponivelException(
					"extrator recusou a credencial; verifique healthstaff.extrator.api-key");
		}
		if (status.is5xxServerError()) {
			throw new ExtratorIndisponivelException("extrator respondeu " + status.value());
		}

		ErroApi erro;
		try {
			erro = jsonMapper.readValue(resposta.getBody(), ErroApi.class);
		}
		catch (JacksonException e) {
			// 4xx sem o corpo padrão (ex.: 404 de um proxy): endereço errado, não arquivo ruim.
			throw new ExtratorIndisponivelException("extrator respondeu " + status.value() + " fora do contrato", e);
		}
		if (erro == null || erro.getCodigo() == null) {
			throw new ExtratorIndisponivelException("extrator respondeu " + status.value() + " fora do contrato");
		}
		log.info("extração recusada pelo extrator: status={} codigo={} campo={}",
				status.value(), erro.getCodigo(), erro.getCampo());
		throw new ExtracaoRejeitadaException(status.value(), erro);
	}
}
