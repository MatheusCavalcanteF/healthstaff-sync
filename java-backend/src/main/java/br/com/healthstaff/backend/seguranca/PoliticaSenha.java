package br.com.healthstaff.backend.seguranca;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Regras de senha, as mesmas para o admin inicial e para o cadastro pela API. */
final class PoliticaSenha {

	static final int TAMANHO_MINIMO = 12;

	/** O BCrypt só aceita até 72 bytes; acima disso a senha nem é conferida. */
	static final int MAXIMO_BYTES = 72;

	private PoliticaSenha() {
	}

	/** O motivo de a senha ser recusada, ou vazio se ela serve. */
	static Optional<String> problema(String senha) {
		if (senha == null || senha.length() < TAMANHO_MINIMO) {
			return Optional.of("a senha precisa ter pelo menos " + TAMANHO_MINIMO + " caracteres");
		}
		if (excedeLimite(senha)) {
			return Optional.of("a senha pode ter no máximo " + MAXIMO_BYTES + " bytes");
		}
		return Optional.empty();
	}

	static boolean excedeLimite(String senha) {
		return senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES;
	}
}
