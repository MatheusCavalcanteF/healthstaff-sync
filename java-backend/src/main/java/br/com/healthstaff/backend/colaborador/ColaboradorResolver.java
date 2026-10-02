package br.com.healthstaff.backend.colaborador;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Encontra (ou cria) o colaborador correspondente a uma linha importada.
 *
 * <p>Regras, em ordem:
 * <ol>
 * <li>Com matrícula: o cadastro com essa matrícula; senão, o cadastro
 * provisório de mesmo nome normalizado, que é <b>promovido</b> (ganha a
 * matrícula e mantém o histórico); senão, um cadastro novo.</li>
 * <li>Sem matrícula: o cadastro provisório de mesmo nome; senão, o único
 * cadastro com matrícula de nome normalizado idêntico (escala importada sem o
 * Two junto); senão, um provisório novo. Com dois cadastros de mesmo nome
 * (homônimos), não adivinha: cria provisório.</li>
 * </ol>
 * A associação aproximada (fuzzy) é feita antes, no extrator; aqui só há
 * igualdade exata, para nunca juntar duas pessoas diferentes.
 */
@Component
public class ColaboradorResolver {

	private final ColaboradorRepository repositorio;

	public ColaboradorResolver(ColaboradorRepository repositorio) {
		this.repositorio = repositorio;
	}

	/**
	 * @param fonteOficial verdadeiro para o relatório do Two: o nome de lá
	 *                     passa a ser o nome do cadastro
	 */
	public Colaborador resolver(DadosColaborador dados, boolean fonteOficial) {
		var colaborador = dados.matricula() != null ? comMatricula(dados) : semMatricula(dados);
		colaborador.complementar(dados, fonteOficial || colaborador.getId() == null);
		return repositorio.save(colaborador);
	}

	private Colaborador comMatricula(DadosColaborador dados) {
		return repositorio.findByMatricula(dados.matricula())
				.or(() -> repositorio.findByMatriculaIsNullAndNomeNormalizado(dados.nomeNormalizado())
						.map(provisorio -> {
							provisorio.promover(dados.matricula());
							return provisorio;
						}))
				.orElseGet(() -> new Colaborador(dados.matricula(), dados.nome(), dados.nomeNormalizado()));
	}

	private Colaborador semMatricula(DadosColaborador dados) {
		return repositorio.findByMatriculaIsNullAndNomeNormalizado(dados.nomeNormalizado())
				.or(() -> {
					var homonimos = repositorio.findByMatriculaIsNotNullAndNomeNormalizado(dados.nomeNormalizado());
					return homonimos.size() == 1 ? Optional.of(homonimos.getFirst()) : Optional.empty();
				})
				.orElseGet(() -> new Colaborador(null, dados.nome(), dados.nomeNormalizado()));
	}
}
