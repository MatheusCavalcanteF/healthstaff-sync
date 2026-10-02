package br.com.healthstaff.backend.web;

/** Parâmetro de consulta fora do permitido (vira 400 PARAMETRO_INVALIDO). */
public class ParametroInvalidoException extends RuntimeException {

	public ParametroInvalidoException(String mensagem) {
		super(mensagem);
	}
}
