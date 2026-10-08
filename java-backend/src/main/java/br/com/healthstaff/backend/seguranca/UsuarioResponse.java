package br.com.healthstaff.backend.seguranca;

import java.util.UUID;

/** Dados públicos do usuário (sem hash de senha). */
public record UsuarioResponse(UUID id, String nome, String email, Perfil perfil) {

	static UsuarioResponse de(Usuario usuario) {
		return new UsuarioResponse(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getPerfil());
	}
}
