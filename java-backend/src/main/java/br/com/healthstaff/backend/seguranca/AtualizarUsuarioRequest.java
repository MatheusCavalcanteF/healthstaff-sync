package br.com.healthstaff.backend.seguranca;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code PUT /api/v1/usuarios/{id}}.
 *
 * @param senha opcional: nula ou em branco mantém a senha atual
 */
public record AtualizarUsuarioRequest(
		@NotBlank @Size(max = 255) String nome,
		@NotBlank @Email @Size(max = 255) String email,
		@NotNull Perfil perfil,
		String senha) {

	public AtualizarUsuarioRequest {
		email = email == null ? null : email.strip();
	}

	boolean trocaSenha() {
		return senha != null && !senha.isBlank();
	}

	@Override
	public String toString() {
		return "AtualizarUsuarioRequest[nome=" + nome + ", email=" + email + ", perfil=" + perfil + "]";
	}
}
