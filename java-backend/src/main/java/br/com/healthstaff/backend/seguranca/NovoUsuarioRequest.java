package br.com.healthstaff.backend.seguranca;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo de {@code POST /api/v1/usuarios}. A senha segue a {@link PoliticaSenha}. */
public record NovoUsuarioRequest(
		@NotBlank @Size(max = 255) String nome,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank String senha,
		@NotNull Perfil perfil) {

	public NovoUsuarioRequest {
		email = email == null ? null : email.strip();
	}

	@Override
	public String toString() {
		return "NovoUsuarioRequest[nome=" + nome + ", email=" + email + ", perfil=" + perfil + "]";
	}
}
