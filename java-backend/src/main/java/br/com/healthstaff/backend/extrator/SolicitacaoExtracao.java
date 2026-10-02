package br.com.healthstaff.backend.extrator;

import java.time.LocalDate;
import java.util.List;

/**
 * Pedido de extração.
 *
 * @param escala              escala em grade mensal (PDF ou Excel), opcional
 * @param bancoHoras          relatório de Banco de Horas do Two (Excel ou CSV), opcional
 * @param dataReferenciaPonto data a que os saldos se referem; nula = data da importação
 * @param cadastro            pessoas já conhecidas (matrícula + nome) para o extrator
 *                            associar nomes da escala; vazio = só o que vier no Banco de Horas
 */
public record SolicitacaoExtracao(
		ArquivoParaExtracao escala,
		ArquivoParaExtracao bancoHoras,
		LocalDate dataReferenciaPonto,
		List<PessoaConhecida> cadastro) {

	public SolicitacaoExtracao {
		if (escala == null && bancoHoras == null) {
			throw new IllegalArgumentException("informe ao menos a escala ou o banco de horas");
		}
		cadastro = cadastro == null ? List.of() : List.copyOf(cadastro);
	}

	public SolicitacaoExtracao(ArquivoParaExtracao escala, ArquivoParaExtracao bancoHoras, LocalDate dataReferenciaPonto) {
		this(escala, bancoHoras, dataReferenciaPonto, List.of());
	}

	public static SolicitacaoExtracao somenteEscala(ArquivoParaExtracao escala) {
		return new SolicitacaoExtracao(escala, null, null);
	}

	public static SolicitacaoExtracao somenteEscala(ArquivoParaExtracao escala, List<PessoaConhecida> cadastro) {
		return new SolicitacaoExtracao(escala, null, null, cadastro);
	}

	public static SolicitacaoExtracao somenteBancoHoras(ArquivoParaExtracao bancoHoras, LocalDate dataReferencia) {
		return new SolicitacaoExtracao(null, bancoHoras, dataReferencia);
	}
}
