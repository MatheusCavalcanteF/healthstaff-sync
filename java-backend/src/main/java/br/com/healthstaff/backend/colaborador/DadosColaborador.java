package br.com.healthstaff.backend.colaborador;

import java.util.Objects;

import br.com.healthstaff.backend.extrator.contrato.Colaborador;

/** Dados de identificação de um colaborador como vieram numa importação. */
public record DadosColaborador(
		String matricula,
		String nome,
		String nomeNormalizado,
		String registroConselho,
		String cargo,
		String jornada) {

	public DadosColaborador {
		Objects.requireNonNull(nome, "nome");
		Objects.requireNonNull(nomeNormalizado, "nomeNormalizado");
		matricula = vazioParaNulo(matricula);
	}

	public static DadosColaborador de(Colaborador colaborador) {
		return new DadosColaborador(
				colaborador.getMatricula(),
				colaborador.getNome(),
				colaborador.getNomeNormalizado(),
				colaborador.getRegistroConselho(),
				colaborador.getCargo(),
				colaborador.getJornada());
	}

	private static String vazioParaNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}
}
