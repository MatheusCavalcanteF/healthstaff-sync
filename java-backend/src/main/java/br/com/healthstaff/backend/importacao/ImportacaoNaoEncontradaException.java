package br.com.healthstaff.backend.importacao;

import java.util.UUID;

public class ImportacaoNaoEncontradaException extends RuntimeException {

	public ImportacaoNaoEncontradaException(UUID id) {
		super("importação " + id + " não encontrada");
	}
}
