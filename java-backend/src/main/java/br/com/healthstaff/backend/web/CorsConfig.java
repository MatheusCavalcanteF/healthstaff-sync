package br.com.healthstaff.backend.web;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS da API para o frontend (Next.js), que roda em outra origem.
 *
 * <p>Origens explícitas (nunca "*"), configuráveis por ambiente. O cabeçalho
 * {@code Location} é exposto: as importações respondem 201 com ele, e sem
 * {@code exposedHeaders} o navegador o esconde do JavaScript.
 *
 * <p>Quem aplica é o filtro de CORS do Spring Security ({@code http.cors()} em
 * {@code seguranca.SecurityConfig}), antes da autenticação: o preflight passa
 * sem token e as respostas 401/403 também levam os cabeçalhos.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsConfig.CorsProperties.class)
class CorsConfig {

	/**
	 * @param origensPermitidas origens do frontend (ex.: http://localhost:3000)
	 * @param cachePreflight    por quanto tempo o navegador reaproveita a resposta do preflight
	 */
	@ConfigurationProperties("healthstaff.cors")
	record CorsProperties(
			@DefaultValue("http://localhost:3000") List<String> origensPermitidas,
			@DefaultValue("1h") Duration cachePreflight) {
	}

	private final CorsProperties propriedades;

	CorsConfig(CorsProperties propriedades) {
		this.propriedades = propriedades;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		var configuracao = new CorsConfiguration();
		configuracao.setAllowedOrigins(propriedades.origensPermitidas());
		configuracao.addAllowedMethod(CorsConfiguration.ALL);
		configuracao.addAllowedHeader(CorsConfiguration.ALL);
		configuracao.addExposedHeader(HttpHeaders.LOCATION);
		configuracao.setMaxAge(propriedades.cachePreflight());
		var origem = new UrlBasedCorsConfigurationSource();
		origem.registerCorsConfiguration("/api/**", configuracao);
		return origem;
	}
}
