package br.com.healthstaff.backend.seguranca;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login (emite o JWT) e dados do usuário logado. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	/** O BCrypt só aceita até 72 bytes; acima disso a senha nem é conferida. */
	static final int MAXIMO_BYTES_SENHA = 72;

	private final AuthenticationManager autenticacao;
	private final TokenService tokens;

	AuthController(AuthenticationManager autenticacao, TokenService tokens) {
		this.autenticacao = autenticacao;
		this.tokens = tokens;
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest login) {
		if (login.senha().getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_SENHA) {
			throw new CredenciaisInvalidasException();
		}
		try {
			var autenticado = autenticacao.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.senha()));
			return tokens.emitir((Usuario) autenticado.getPrincipal());
		}
		catch (AuthenticationException e) {
			throw new CredenciaisInvalidasException();
		}
	}

	@GetMapping("/eu")
	UsuarioResponse eu(@AuthenticationPrincipal Usuario usuario) {
		return UsuarioResponse.de(usuario);
	}
}
