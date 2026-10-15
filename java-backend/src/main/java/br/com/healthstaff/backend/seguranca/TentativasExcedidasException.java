package br.com.healthstaff.backend.seguranca;

import java.time.Duration;

/** Login bloqueado por excesso de tentativas sem acerto (429 com {@code Retry-After}). */
public class TentativasExcedidasException extends RuntimeException {

	private final long segundosParaNovaTentativa;

	TentativasExcedidasException(Duration espera) {
		this(Math.max(1, (espera.toMillis() + 999) / 1000));
	}

	private TentativasExcedidasException(long segundos) {
		super("muitas tentativas de login; tente de novo em " + formatar(segundos));
		this.segundosParaNovaTentativa = segundos;
	}

	/** Arredondado para cima, no mínimo 1: valor do cabeçalho {@code Retry-After}. */
	public long getSegundosParaNovaTentativa() {
		return segundosParaNovaTentativa;
	}

	private static String formatar(long segundos) {
		return segundos < 60 ? segundos + " s" : (segundos + 59) / 60 + " min";
	}
}
