package br.com.healthstaff.backend.seguranca;

import java.time.Instant;

/**
 * Resposta do login.
 *
 * @param token    JWT para o cabeçalho {@code Authorization: Bearer <token>}
 * @param tipo     sempre "Bearer"
 * @param expiraEm instante em que o token deixa de valer (sem renovação: depois, novo login)
 */
public record TokenResponse(String token, String tipo, Instant expiraEm, UsuarioResponse usuario) {

	static TokenResponse bearer(String token, Instant expiraEm, Usuario usuario) {
		return new TokenResponse(token, "Bearer", expiraEm, UsuarioResponse.de(usuario));
	}
}
