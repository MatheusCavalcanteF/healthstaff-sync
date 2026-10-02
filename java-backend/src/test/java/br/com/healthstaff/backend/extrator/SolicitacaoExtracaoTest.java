package br.com.healthstaff.backend.extrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.core.io.ByteArrayResource;

class SolicitacaoExtracaoTest {

	static final ArquivoParaExtracao ARQUIVO = new ArquivoParaExtracao("escala.xlsx", new ByteArrayResource(new byte[] { 1 }));

	@Test
	void exigeAoMenosUmArquivo() {
		assertThatThrownBy(() -> new SolicitacaoExtracao(null, null, LocalDate.now()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fabricas() {
		assertThat(SolicitacaoExtracao.somenteEscala(ARQUIVO).bancoHoras()).isNull();
		var ponto = SolicitacaoExtracao.somenteBancoHoras(ARQUIVO, LocalDate.of(2026, 10, 1));
		assertThat(ponto.escala()).isNull();
		assertThat(ponto.dataReferenciaPonto()).isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	void arquivoExigeNomeComExtensao(String nome) {
		assertThatThrownBy(() -> new ArquivoParaExtracao(nome, new ByteArrayResource(new byte[0])))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void arquivoExigeConteudo() {
		assertThatThrownBy(() -> new ArquivoParaExtracao("escala.xlsx", null))
				.isInstanceOf(NullPointerException.class);
	}
}
