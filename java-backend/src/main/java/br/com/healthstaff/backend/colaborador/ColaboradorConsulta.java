package br.com.healthstaff.backend.colaborador;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.ColaboradorResposta.Saldo;
import br.com.healthstaff.backend.colaborador.ColaboradorResposta.Vinculo;
import br.com.healthstaff.backend.web.Pagina;
import br.com.healthstaff.backend.web.ParametroInvalidoException;

/** Listagem de colaboradores com filtros, paginação e saldo atual do Two. */
@Service
public class ColaboradorConsulta {

	public static final int TAMANHO_MAXIMO = 200;

	private final ColaboradorRepository colaboradores;

	public ColaboradorConsulta(ColaboradorRepository colaboradores) {
		this.colaboradores = colaboradores;
	}

	/**
	 * @param vinculo filtro opcional (OFICIAL/PROVISORIO)
	 * @param busca   trecho do nome (sem diferenciar acento/maiúscula) ou início da matrícula
	 */
	@Transactional(readOnly = true)
	public Pagina<ColaboradorResposta> listar(Vinculo vinculo, String busca, int pagina, int tamanho) {
		if (pagina < 0) {
			throw new ParametroInvalidoException("'pagina' deve ser 0 ou maior");
		}
		if (tamanho < 1 || tamanho > TAMANHO_MAXIMO) {
			throw new ParametroInvalidoException("'tamanho' deve estar entre 1 e " + TAMANHO_MAXIMO);
		}

		var filtro = Specification.allOf(porVinculo(vinculo), porBusca(busca));
		var ordem = Sort.by("nome", "id");
		var resultado = colaboradores.findAll(filtro, PageRequest.of(pagina, tamanho, ordem));

		return Pagina.de(resultado, lista -> {
			var saldos = saldosAtuais(lista);
			return lista.stream().map(c -> ColaboradorResposta.de(c, saldos.get(c.getId()))).toList();
		});
	}

	private Map<Long, Saldo> saldosAtuais(List<Colaborador> lista) {
		if (lista.isEmpty()) {
			return Map.of();
		}
		return colaboradores.saldosAtuais(lista.stream().map(Colaborador::getId).toList()).stream()
				.collect(Collectors.toMap(ColaboradorRepository.SaldoAtual::getColaboradorId,
						s -> new Saldo(s.getMinutos(), s.getDataReferencia()), (a, b) -> a));
	}

	private static Specification<Colaborador> porVinculo(Vinculo vinculo) {
		if (vinculo == null) {
			return Specification.unrestricted();
		}
		return (raiz, consulta, cb) -> vinculo == Vinculo.PROVISORIO
				? cb.isNull(raiz.get("matricula"))
				: cb.isNotNull(raiz.get("matricula"));
	}

	private static Specification<Colaborador> porBusca(String busca) {
		if (busca == null || busca.isBlank()) {
			return Specification.unrestricted();
		}
		var termo = escaparLike(normalizar(busca));
		var bruto = escaparLike(busca.strip());
		return (raiz, consulta, cb) -> cb.or(
				cb.like(raiz.get("nomeNormalizado"), "%" + termo + "%", '\\'),
				cb.like(raiz.get("matricula"), bruto + "%", '\\'));
	}

	/** Mesma normalização do extrator: sem acento, maiúsculas, espaços simples. */
	static String normalizar(String texto) {
		var semAcento = Normalizer.normalize(texto, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		return semAcento.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}

	private static String escaparLike(String texto) {
		return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
