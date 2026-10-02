package br.com.healthstaff.backend.web;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS da API para o frontend (Next.js), que roda em outra origem.
 *
 * <p>Origens explícitas (nunca "*"), configuráveis por ambiente. O cabeçalho
 * {@code Location} é exposto: as importações respondem 201 com ele, e sem
 * {@code exposedHeaders} o navegador o esconde do JavaScript.
 *
 * <p>Ao adicionar o Spring Security, habilite {@code http.cors()} para que o
 * filtro de segurança use esta mesma configuração (senão o preflight é
 * barrado antes de chegar aqui).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsConfig.CorsProperties.class)
class CorsConfig implements WebMvcConfigurer {

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

	@Override
	public void addCorsMappings(CorsRegistry registro) {
		registro.addMapping("/api/**")
				.allowedOrigins(propriedades.origensPermitidas().toArray(String[]::new))
				.allowedMethods("*")
				.allowedHeaders("*")
				.exposedHeaders(HttpHeaders.LOCATION)
				.maxAge(propriedades.cachePreflight().toSeconds());
	}
}
