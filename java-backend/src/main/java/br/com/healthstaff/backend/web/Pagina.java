package br.com.healthstaff.backend.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Página de resultados com formato estável para o frontend (não expõe a
 * estrutura interna do {@code Page} do Spring Data).
 *
 * @param pagina começa em 0
 */
public record Pagina<T>(List<T> conteudo, int pagina, int tamanho, long totalElementos, int totalPaginas) {

	public static <E, T> Pagina<T> de(Page<E> pagina, Function<List<E>, List<T>> conversor) {
		return new Pagina<>(conversor.apply(pagina.getContent()), pagina.getNumber(), pagina.getSize(),
				pagina.getTotalElements(), pagina.getTotalPages());
	}
}
