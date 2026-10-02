package br.com.healthstaff.backend.importacao;

/** Upload que nem chega a ser enviado ao extrator (vazio, sem nome). */
public class ArquivoInvalidoException extends RuntimeException {

	public ArquivoInvalidoException(String mensagem) {
		super(mensagem);
	}
}
