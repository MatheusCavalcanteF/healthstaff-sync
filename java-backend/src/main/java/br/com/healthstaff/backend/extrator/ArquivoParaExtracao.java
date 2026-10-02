package br.com.healthstaff.backend.extrator;

import java.util.Objects;

import org.springframework.core.io.Resource;

/**
 * Arquivo a ser enviado ao extrator. O nome precisa manter a extensão
 * original (.xlsx, .pdf, .csv): o extrator escolhe a estratégia por ela.
 *
 * <p>Use {@code multipartFile.getResource()} para repassar um upload do
 * frontend sem carregá-lo inteiro em memória.
 */
public record ArquivoParaExtracao(String nome, Resource conteudo) {

	public ArquivoParaExtracao {
		Objects.requireNonNull(conteudo, "conteudo");
		if (nome == null || nome.isBlank()) {
			throw new IllegalArgumentException("nome do arquivo é obrigatório (com extensão)");
		}
	}
}
