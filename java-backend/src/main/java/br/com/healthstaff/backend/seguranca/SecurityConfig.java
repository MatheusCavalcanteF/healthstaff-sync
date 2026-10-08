package br.com.healthstaff.backend.seguranca;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * API sem estado: cada requisição traz {@code Authorization: Bearer <jwt>}.
 *
 * <ul>
 * <li>Livres: {@code POST /api/v1/auth/login} e o healthcheck do Actuator.</li>
 * <li>{@code GET /api/v1/**}: qualquer usuário autenticado.</li>
 * <li>Demais métodos em {@code /api/v1/**} (importações, reprocessar análise):
 * só ADMIN e GESTOR_RH. Endpoint de escrita novo já nasce protegido.</li>
 * <li>Qualquer outra rota: negada.</li>
 * </ul>
 *
 * <p>CSRF desligado: a API não usa cookie de sessão, então não há credencial
 * que o navegador envie sozinho. O CORS vem de {@code web.CorsConfig}; o filtro
 * de CORS roda antes da autenticação, de modo que o preflight passa sem token
 * e as respostas 401/403 também levam os cabeçalhos CORS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SegurancaProperties.class)
class SecurityConfig {

	@Bean
	SecurityFilterChain filtroDeSeguranca(HttpSecurity http, UsuarioDoToken usuarioDoToken,
			AuthenticationEntryPoint naoAutenticado, AccessDeniedHandler acessoNegado) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(rotas -> rotas
						// Encaminhamento interno para /error: a requisição original já foi autorizada.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
						.permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
						.requestMatchers("/api/v1/**").hasAnyRole(Perfil.ADMIN.name(), Perfil.GESTOR_RH.name())
						.anyRequest().denyAll())
				.oauth2ResourceServer(recurso -> recurso
						.jwt(jwt -> jwt.jwtAuthenticationConverter(usuarioDoToken))
						.authenticationEntryPoint(naoAutenticado)
						.accessDeniedHandler(acessoNegado))
				.exceptionHandling(erros -> erros
						.authenticationEntryPoint(naoAutenticado)
						.accessDeniedHandler(acessoNegado))
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder(TokenService tokens) {
		return tokens::validar;
	}

	/**
	 * Erros de autenticação/autorização saem no mesmo Problem Details (com
	 * {@code codigo}) do resto da API: os filtros repassam a exceção ao
	 * {@code ErrosApiHandler}, como se viesse de um controller.
	 */
	@Bean
	AuthenticationEntryPoint naoAutenticado(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedor) {
		return (requisicao, resposta, erro) -> resolvedor.resolveException(requisicao, resposta, null, erro);
	}

	@Bean
	AccessDeniedHandler acessoNegado(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedor) {
		return (requisicao, resposta, erro) -> resolvedor.resolveException(requisicao, resposta, null, erro);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** Login por e-mail e senha (usado só pelo {@link AuthController}). */
	@Bean
	AuthenticationManager authenticationManager(UsuarioRepository usuarios, PasswordEncoder senhas) {
		UserDetailsService porEmail = email -> usuarios.findByEmail(Usuario.normalizarEmail(email))
				.orElseThrow(() -> new UsernameNotFoundException("usuário não encontrado"));
		var provedor = new DaoAuthenticationProvider(porEmail);
		provedor.setPasswordEncoder(senhas);
		// Senha conferida antes de "ativo": senão um usuário inativo seria recusado sem
		// passar pelo BCrypt (resposta mais rápida), e o tempo revelaria que a conta existe.
		provedor.setPreAuthenticationChecks(usuario -> {
		});
		provedor.setPostAuthenticationChecks(usuario -> {
			if (!usuario.isEnabled()) {
				throw new DisabledException("usuário inativo");
			}
		});
		return new ProviderManager(provedor);
	}
}
