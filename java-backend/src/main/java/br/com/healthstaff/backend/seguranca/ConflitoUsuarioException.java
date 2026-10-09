package br.com.healthstaff.backend.seguranca;

/** Operação recusada pelo estado atual dos usuários (vira 409 com o código). */
public class ConflitoUsuarioException extends RuntimeException {

	private final String codigo;

	private ConflitoUsuarioException(String codigo, String mensagem) {
		super(mensagem);
		this.codigo = codigo;
	}

	static ConflitoUsuarioException emailJaCadastrado(String email) {
		return new ConflitoUsuarioException("EMAIL_JA_CADASTRADO", "já existe um usuário com o e-mail " + email);
	}

	static ConflitoUsuarioException ultimoAdmin() {
		return new ConflitoUsuarioException("ULTIMO_ADMIN",
				"este é o último administrador ativo; cadastre ou ative outro antes");
	}

	static ConflitoUsuarioException alteracaoPropria() {
		return new ConflitoUsuarioException("ALTERACAO_PROPRIA_BLOQUEADA",
				"você não pode desativar a própria conta nem tirar o próprio perfil de administrador; "
						+ "peça a outro administrador");
	}

	public String getCodigo() {
		return codigo;
	}
}
