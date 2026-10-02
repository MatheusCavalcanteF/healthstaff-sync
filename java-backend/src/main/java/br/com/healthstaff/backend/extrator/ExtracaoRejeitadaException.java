package br.com.healthstaff.backend.extrator;

import br.com.healthstaff.backend.extrator.contrato.ErroApi;

/**
 * O extrator recusou o conteúdo enviado (arquivo no campo errado, formato não
 * reconhecido, muito grande, competência ausente...). É um erro do usuário:
 * deve voltar ao frontend com a mensagem de {@link #getErro()}.
 */
public class ExtracaoRejeitadaException extends RuntimeException {

	private final int status;
	private final transient ErroApi erro;

	public ExtracaoRejeitadaException(int status, ErroApi erro) {
		super("%s: %s".formatted(erro.getCodigo(), erro.getMensagem()));
		this.status = status;
		this.erro = erro;
	}

	/** Status HTTP devolvido pelo extrator (422 ou 413). */
	public int getStatus() {
		return status;
	}

	public ErroApi getErro() {
		return erro;
	}
}
