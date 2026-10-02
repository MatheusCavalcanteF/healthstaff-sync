package br.com.healthstaff.backend.colaborador;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.healthstaff.backend.colaborador.ColaboradorResposta.Vinculo;
import br.com.healthstaff.backend.web.Pagina;

/** Cadastro de colaboradores (somente leitura). */
@RestController
@RequestMapping("/api/v1/colaboradores")
class ColaboradorController {

	private final ColaboradorConsulta consulta;

	ColaboradorController(ColaboradorConsulta consulta) {
		this.consulta = consulta;
	}

	/**
	 * Lista paginada, ordenada por nome.
	 *
	 * @param vinculo OFICIAL ou PROVISORIO (opcional)
	 * @param busca   trecho do nome (acento e maiúsculas indiferentes) ou início da matrícula
	 * @param pagina  começa em 0
	 * @param tamanho de 1 a 200
	 */
	@GetMapping
	Pagina<ColaboradorResposta> listar(
			@RequestParam(required = false) Vinculo vinculo,
			@RequestParam(required = false) String busca,
			@RequestParam(defaultValue = "0") int pagina,
			@RequestParam(defaultValue = "50") int tamanho) {
		return consulta.listar(vinculo, busca, pagina, tamanho);
	}
}
