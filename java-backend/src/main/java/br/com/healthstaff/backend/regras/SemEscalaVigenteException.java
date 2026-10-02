package br.com.healthstaff.backend.regras;

import java.time.YearMonth;

public class SemEscalaVigenteException extends RuntimeException {

	public SemEscalaVigenteException(YearMonth competencia) {
		super("não há escala vigente importada para a competência " + competencia);
	}
}
