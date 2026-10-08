package br.com.healthstaff.backend.seguranca;

/**
 * Login recusado. Uma só mensagem para e-mail inexistente, senha errada ou
 * usuário inativo: a resposta não revela quais e-mails têm conta.
 */
public class CredenciaisInvalidasException extends RuntimeException {

	CredenciaisInvalidasException() {
		super("e-mail ou senha inválidos");
	}
}
