package br.com.healthstaff.backend.seguranca;

import java.util.UUID;

public class UsuarioNaoEncontradoException extends RuntimeException {

	UsuarioNaoEncontradoException(UUID id) {
		super("usuário " + id + " não encontrado");
	}
}
