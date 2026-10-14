package br.com.healthstaff.backend.extrator;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtratorProperties.class)
class ExtratorConfig {

	static final String CABECALHO_API_KEY = "X-API-Key";

	/**
	 * RestClient exclusivo do extrator, a partir do builder do Spring Boot
	 * (conversores Jackson 3 já configurados), com timeouts próprios.
	 */
	@Bean
	RestClient extratorRestClient(RestClient.Builder builder, ExtratorProperties propriedades) {
		return criar(builder, propriedades, propriedades.timeoutConexao(), propriedades.timeoutLeitura());
	}

	/** Só para o {@code GET /health}: o healthcheck não pode herdar o prazo longo da extração. */
	@Bean
	RestClient extratorSaudeRestClient(RestClient.Builder builder, ExtratorProperties propriedades) {
		return criar(builder, propriedades, propriedades.timeoutSaude(), propriedades.timeoutSaude());
	}

	private static RestClient criar(RestClient.Builder builder, ExtratorProperties propriedades,
			Duration timeoutConexao, Duration timeoutLeitura) {
		var httpClient = HttpClient.newBuilder()
				// O uvicorn não aceita o upgrade h2c que o cliente do JDK tenta em http://.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(timeoutConexao)
				.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(timeoutLeitura);

		var extrator = builder.clone()
				.baseUrl(propriedades.url().toString())
				.requestFactory(requestFactory);
		if (propriedades.possuiApiKey()) {
			extrator.defaultHeader(CABECALHO_API_KEY, propriedades.apiKey());
		}
		return extrator.build();
	}
}
