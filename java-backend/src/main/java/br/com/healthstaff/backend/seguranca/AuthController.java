package br.com.healthstaff.backend.seguranca;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login (emite o JWT), logout (revoga) e dados do usuário logado. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AuthenticationManager autenticacao;
	private final TokenService tokens;
	private final LimiteTentativasLogin limite;
	private final UsuarioService usuarios;

	AuthController(AuthenticationManager autenticacao, TokenService tokens, LimiteTentativasLogin limite,
			UsuarioService usuarios) {
		this.autenticacao = autenticacao;
		this.tokens = tokens;
		this.limite = limite;
		this.usuarios = usuarios;
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest login) {
		// Antes de tudo: e-mail bloqueado recebe 429 sem nem passar pelo BCrypt.
		limite.registrarTentativa(login.email());
		if (PoliticaSenha.excedeLimite(login.senha())) {
			throw new CredenciaisInvalidasException();
		}
		try {
			var autenticado = autenticacao.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.senha()));
			limite.registrarAcerto(login.email());
			return tokens.emitir((Usuario) autenticado.getPrincipal());
		}
		catch (AuthenticationException e) {
			throw new CredenciaisInvalidasException();
		}
	}

	/**
	 * Logout que vale no servidor: incrementa a versão das credenciais, e o token
	 * (com os demais do usuário) deixa de valer na hora, sem esperar a expiração.
	 */
	@PostMapping("/sair")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void sair(@AuthenticationPrincipal Usuario usuario) {
		usuarios.encerrarSessoes(usuario.getId());
	}

	@GetMapping("/eu")
	UsuarioResponse eu(@AuthenticationPrincipal Usuario usuario) {
		return UsuarioResponse.de(usuario);
	}
}
