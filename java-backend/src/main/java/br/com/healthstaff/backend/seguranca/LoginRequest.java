package br.com.healthstaff.backend.seguranca;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Corpo de {@code POST /api/v1/auth/login}. */
public record LoginRequest(@NotBlank @Email String email, @NotBlank String senha) {

	public LoginRequest {
		// Espaços de copiar e colar não reprovam o login; a senha é usada como veio.
		email = email == null ? null : email.strip();
	}

	@Override
	public String toString() {
		// Nunca deixar a senha cair em log por acidente.
		return "LoginRequest[email=" + email + "]";
	}
}
