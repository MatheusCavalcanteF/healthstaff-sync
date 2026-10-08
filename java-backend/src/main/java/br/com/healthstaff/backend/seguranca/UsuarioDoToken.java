package br.com.healthstaff.backend.seguranca;

import java.util.Optional;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Transforma um JWT válido no usuário autenticado, lido do banco: usuário
 * removido ou inativo perde o acesso mesmo com token dentro da validade, e as
 * autoridades vêm do perfil atual, não do que estava no token.
 */
@Component
class UsuarioDoToken implements Converter<Jwt, AbstractAuthenticationToken> {

	private final UsuarioRepository usuarios;

	UsuarioDoToken(UsuarioRepository usuarios) {
		this.usuarios = usuarios;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		var usuario = id(jwt.getSubject())
				.flatMap(usuarios::findById)
				.filter(Usuario::isEnabled)
				.orElseThrow(() -> new InvalidBearerTokenException("usuário do token inexistente ou inativo"));
		return UsernamePasswordAuthenticationToken.authenticated(usuario, jwt, usuario.getAuthorities());
	}

	private static Optional<UUID> id(String sujeito) {
		try {
			return Optional.of(UUID.fromString(sujeito));
		}
		catch (IllegalArgumentException | NullPointerException e) {
			return Optional.empty();
		}
	}
}
