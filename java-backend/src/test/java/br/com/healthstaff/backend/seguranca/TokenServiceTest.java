package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.security.oauth2.jwt.JwtException;

class TokenServiceTest {

	static final String SEGREDO = "segredo-de-teste-com-pelo-menos-32-bytes!";
	static final Instant AGORA = Instant.parse("2026-10-08T12:00:00Z");

	final Usuario usuario = Usuario.novo("Ana Teste", "ana@teste.local", "hash", Perfil.AUDITOR);

	static TokenService servico(String segredo, Duration expiracao, Instant agora) {
		return new TokenService(new SegurancaProperties.Jwt(segredo, expiracao), Clock.fixed(agora, ZoneOffset.UTC));
	}

	@Test
	void tokenEmitidoEValidadoComClaimsDoUsuario() {
		var servico = servico(SEGREDO, Duration.ofHours(8), AGORA);

		var resposta = servico.emitir(usuario);
		var jwt = servico.validar(resposta.token());

		assertThat(resposta.tipo()).isEqualTo("Bearer");
		assertThat(resposta.expiraEm()).isEqualTo(AGORA.plus(Duration.ofHours(8)));
		assertThat(resposta.usuario().email()).isEqualTo("ana@teste.local");
		assertThat(jwt.getSubject()).isEqualTo(usuario.getId().toString());
		assertThat(jwt.getClaimAsString("perfil")).isEqualTo("AUDITOR");
		assertThat(jwt.getClaimAsString("nome")).isEqualTo("Ana Teste");
		assertThat(jwt.<Number>getClaim("ver").intValue()).isEqualTo(usuario.getVersaoCredenciais());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("healthstaff-backend");
		assertThat(jwt.getIssuedAt()).isEqualTo(AGORA);
		assertThat(jwt.getExpiresAt()).isEqualTo(resposta.expiraEm());
		// Nada de e-mail no token: ele só precisa identificar o usuário.
		assertThat(jwt.getClaims()).doesNotContainKey("email");
	}

	@Test
	void tokenExpiradoERecusado() {
		var token = servico(SEGREDO, Duration.ofHours(8), AGORA).emitir(usuario).token();
		var noDiaSeguinte = servico(SEGREDO, Duration.ofHours(8), AGORA.plus(Duration.ofHours(9)));

		assertThatThrownBy(() -> noDiaSeguinte.validar(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenAssinadoComOutraChaveERecusado() {
		var token = servico("outro-segredo-tambem-com-32-bytes-ou-mais", Duration.ofHours(8), AGORA)
				.emitir(usuario).token();

		assertThatThrownBy(() -> servico(SEGREDO, Duration.ofHours(8), AGORA).validar(token))
				.isInstanceOf(JwtException.class);
	}

	@Test
	void tokenAdulteradoERecusado() {
		var servico = servico(SEGREDO, Duration.ofHours(8), AGORA);
		var partes = servico.emitir(usuario).token().split("\\.");
		// Troca o perfil no payload mantendo a assinatura original.
		var payload = new String(Base64.getUrlDecoder().decode(partes[1]))
				.replace("AUDITOR", "ADMIN");
		var adulterado = partes[0] + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes()) + "." + partes[2];

		assertThatThrownBy(() -> servico.validar(adulterado)).isInstanceOf(JwtException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "curto-demais")
	void segredoAusenteOuCurtoImpedeASubida(String segredo) {
		assertThatThrownBy(() -> new SegurancaProperties.Jwt(segredo, Duration.ofHours(8)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("HEALTHSTAFF_JWT_SECRET");
	}

	@Test
	void expiracaoPrecisaSerPositiva() {
		assertThatThrownBy(() -> new SegurancaProperties.Jwt(SEGREDO, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
