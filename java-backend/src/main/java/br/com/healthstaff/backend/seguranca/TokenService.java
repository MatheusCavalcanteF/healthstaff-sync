package br.com.healthstaff.backend.seguranca;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Emite e valida os JWT da API (HS256, chave em {@code healthstaff.security.jwt.secret}).
 *
 * <p>O token carrega o id do usuário ({@code sub}), o perfil e o nome, para o
 * frontend exibir. Quem decide o acesso é o banco: a cada requisição o usuário
 * é recarregado ({@link UsuarioDoToken}), então desativar alguém ou trocar o
 * perfil vale na hora, sem esperar o token expirar.
 */
@Service
public class TokenService {

	static final String EMISSOR = "healthstaff-backend";
	static final String CLAIM_PERFIL = "perfil";
	static final String CLAIM_NOME = "nome";

	private final JwtEncoder codificador;
	private final NimbusJwtDecoder decodificador;
	private final SegurancaProperties.Jwt configuracao;
	private final Clock relogio;

	@Autowired
	public TokenService(SegurancaProperties propriedades) {
		this(propriedades.jwt(), Clock.systemUTC());
	}

	TokenService(SegurancaProperties.Jwt configuracao, Clock relogio) {
		this.configuracao = configuracao;
		this.relogio = relogio;
		var chave = new SecretKeySpec(configuracao.chave(), "HmacSHA256");
		this.codificador = new NimbusJwtEncoder(new ImmutableSecret<>(chave));
		this.decodificador = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build();
		var validadeNoTempo = new JwtTimestampValidator();
		validadeNoTempo.setClock(relogio);
		this.decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				validadeNoTempo,
				new JwtIssuerValidator(EMISSOR),
				// Token sem "exp" valeria para sempre.
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)));
	}

	public TokenResponse emitir(Usuario usuario) {
		var agora = relogio.instant();
		var expiraEm = agora.plus(configuracao.expiracao());
		var claims = JwtClaimsSet.builder()
				.issuer(EMISSOR)
				.subject(usuario.getId().toString())
				.issuedAt(agora)
				.expiresAt(expiraEm)
				.claim(CLAIM_PERFIL, usuario.getPerfil().name())
				.claim(CLAIM_NOME, usuario.getNome())
				.build();
		var cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
		var token = codificador.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
		return TokenResponse.bearer(token, expiraEm, usuario);
	}

	/**
	 * Confere assinatura, emissor e validade.
	 *
	 * @throws JwtException token malformado, adulterado ou expirado
	 */
	public Jwt validar(String token) {
		return decodificador.decode(token);
	}
}
