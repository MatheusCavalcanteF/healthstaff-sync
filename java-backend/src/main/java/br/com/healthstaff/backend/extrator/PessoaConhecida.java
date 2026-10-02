package br.com.healthstaff.backend.extrator;

/**
 * Colaborador já cadastrado no backend, enviado ao extrator no campo
 * {@code cadastro} para que ele associe nomes da escala a matrículas mesmo
 * quando o relatório do Two não vem na mesma requisição.
 */
public record PessoaConhecida(String matricula, String nome) {
}
