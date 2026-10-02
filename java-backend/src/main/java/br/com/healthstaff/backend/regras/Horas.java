package br.com.healthstaff.backend.regras;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Formatação de durações nas mensagens dos alertas ("11h", "10h30", "+44h42", "-4h03"). */
final class Horas {

	private Horas() {
	}

	static String formatar(Duration duracao) {
		return formatar((int) duracao.toMinutes());
	}

	static String formatar(int minutos) {
		var sinal = minutos < 0 ? "-" : "";
		int absoluto = Math.abs(minutos);
		int horas = absoluto / 60;
		int resto = absoluto % 60;
		return sinal + horas + "h" + (resto == 0 ? "" : "%02d".formatted(resto));
	}

	private static final DateTimeFormatter MOMENTO = DateTimeFormatter.ofPattern("dd/MM HH:mm");
	private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	static String momento(LocalDateTime instante) {
		return MOMENTO.format(instante);
	}

	static String dia(LocalDate data) {
		return DIA.format(data);
	}

	static String comSinal(int minutos) {
		return (minutos > 0 ? "+" : "") + formatar(minutos);
	}
}
