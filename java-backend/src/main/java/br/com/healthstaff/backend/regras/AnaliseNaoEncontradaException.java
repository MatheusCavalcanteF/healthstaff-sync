package br.com.healthstaff.backend.regras;

import java.time.YearMonth;

public class AnaliseNaoEncontradaException extends RuntimeException {

	public AnaliseNaoEncontradaException(YearMonth competencia) {
		super("nenhuma análise para a competência " + competencia);
	}
}
