package br.com.healthstaff.backend.seguranca;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Usuários fictícios para os testes que passam pela autenticação real. */
public final class UsuariosDeTeste {

	public static final String SENHA = "senha-de-teste-123";

	/** Custo 4 (o mínimo) só para os testes serem rápidos; o custo fica gravado no hash. */
	static final String HASH_SENHA = new BCryptPasswordEncoder(4).encode(SENHA);

	private UsuariosDeTeste() {
	}

	/** O usuário com este e-mail, criado (ativo, com {@link #SENHA}) se ainda não existir. */
	public static Usuario obter(UsuarioRepository usuarios, String email, Perfil perfil) {
		return usuarios.findByEmail(Usuario.normalizarEmail(email))
				.orElseGet(() -> usuarios.save(Usuario.novo("Usuária de Teste", email, HASH_SENHA, perfil)));
	}
}
