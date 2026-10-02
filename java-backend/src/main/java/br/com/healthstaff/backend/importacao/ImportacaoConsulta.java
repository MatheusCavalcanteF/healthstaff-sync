package br.com.healthstaff.backend.importacao;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.bancohoras.SaldoBancoHorasRepository;
import br.com.healthstaff.backend.escala.EscalaRepository;
import br.com.healthstaff.backend.escala.PlantaoRepository;
import br.com.healthstaff.backend.importacao.ImportacaoResposta.ItemInconsistencia;
import br.com.healthstaff.backend.importacao.ImportacaoResposta.VersaoEscala;

/** Monta a visão de uma importação a partir do que está gravado no banco. */
@Service
public class ImportacaoConsulta {

	private final ImportacaoRepository importacoes;
	private final InconsistenciaImportacaoRepository inconsistencias;
	private final EscalaRepository escalas;
	private final PlantaoRepository plantoes;
	private final SaldoBancoHorasRepository saldos;

	public ImportacaoConsulta(ImportacaoRepository importacoes, InconsistenciaImportacaoRepository inconsistencias,
			EscalaRepository escalas, PlantaoRepository plantoes, SaldoBancoHorasRepository saldos) {
		this.importacoes = importacoes;
		this.inconsistencias = inconsistencias;
		this.escalas = escalas;
		this.plantoes = plantoes;
		this.saldos = saldos;
	}

	@Transactional(readOnly = true)
	public ImportacaoResposta detalhar(UUID id, boolean duplicada) {
		var importacao = importacoes.findById(id).orElseThrow(() -> new ImportacaoNaoEncontradaException(id));
		var versoes = escalas.findByImportacaoId(id).stream()
				.map(e -> new VersaoEscala(e.getSetor().getNome(), e.getCompetencia(), e.getVersao(), e.isVigente(),
						plantoes.countByEscala(e)))
				.toList();
		var itens = inconsistencias.findByImportacaoIdOrderById(id).stream()
				.map(ItemInconsistencia::de)
				.toList();
		return new ImportacaoResposta(
				importacao.getId(),
				importacao.getTipoDocumento(),
				importacao.getNomeArquivo(),
				duplicada,
				importacao.getPeriodoInicio(),
				importacao.getPeriodoFim(),
				importacao.getImportadoEm(),
				importacao.getTotalColaboradores(),
				importacao.getTotalPlantoes(),
				importacao.isPossuiErros(),
				versoes,
				saldos.countByImportacaoId(id),
				itens);
	}
}
