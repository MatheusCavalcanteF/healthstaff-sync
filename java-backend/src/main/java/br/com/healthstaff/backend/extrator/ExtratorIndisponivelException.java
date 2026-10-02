package br.com.healthstaff.backend.extrator;

/**
 * Falha de infraestrutura na chamada ao extrator: serviço fora do ar,
 * timeout, credencial inválida, erro interno ou resposta fora do contrato.
 * Não é culpa do usuário; deve virar 502/503 e alerta para a operação.
 */
public class ExtratorIndisponivelException extends RuntimeException {

	public ExtratorIndisponivelException(String mensagem) {
		super(mensagem);
	}

	public ExtratorIndisponivelException(String mensagem, Throwable causa) {
		super(mensagem, causa);
	}
}
