package br.com.healthstaff.backend.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.extrator.ExtratorClient;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;
import br.com.healthstaff.backend.extrator.contrato.Origem;
import br.com.healthstaff.backend.extrator.contrato.TipoDocumento;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Upload simultâneo do mesmo arquivo: as duas requisições passam pela checagem
 * de duplicidade, uma grava e a outra esbarra na chave única do banco. A
 * segunda deve receber a importação da primeira, não um erro.
 */
class ImportarArquivoServiceTest {

	final ImportacaoService importacoes = mock(ImportacaoService.class);
	final ImportacaoConsulta consulta = mock(ImportacaoConsulta.class);
	final ImportacaoRepository repositorio = mock(ImportacaoRepository.class);
	final ImportarArquivoService servico = new ImportarArquivoService(
			mock(ExtratorClient.class), importacoes, consulta, repositorio, mock(ColaboradorRepository.class));

	final String hash = "a".repeat(64);
	final LoteImportacao lote = new LoteImportacao()
			.origem(new Origem().tipoDocumento(TipoDocumento.ESCALA_EXCEL).hashSha256(hash));

	static ImportacaoResposta resposta(UUID id, boolean duplicada) {
		return new ImportacaoResposta(id, "ESCALA_EXCEL", "e.xlsx", duplicada, null, null, null, 0, 0, false,
				List.of(), 0, List.of());
	}

	@Test
	void corridaComOMesmoArquivoDevolveAImportacaoQueVenceu() {
		var vencedora = UUID.randomUUID();
		var existente = mock(Importacao.class);
		when(existente.getId()).thenReturn(vencedora);
		when(importacoes.registrar(lote)).thenThrow(new DataIntegrityViolationException("uk_importacao_arquivo"));
		when(repositorio.findByTipoDocumentoAndHashSha256("ESCALA_EXCEL", hash)).thenReturn(Optional.of(existente));
		when(consulta.detalhar(vencedora, true)).thenReturn(resposta(vencedora, true));

		var resultado = servico.registrar(lote);

		assertThat(resultado.id()).isEqualTo(vencedora);
		assertThat(resultado.duplicada()).isTrue();
	}

	@Test
	void outraViolacaoDeIntegridadeNaoEhMascarada() {
		var violacao = new DataIntegrityViolationException("ck_plantao_horario");
		when(importacoes.registrar(lote)).thenThrow(violacao);
		when(repositorio.findByTipoDocumentoAndHashSha256("ESCALA_EXCEL", hash)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> servico.registrar(lote)).isSameAs(violacao);
	}
}
