package br.com.healthstaff.backend.escala;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.ColaboradorResolver;
import br.com.healthstaff.backend.colaborador.DadosColaborador;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;

/** Registra os setores, as versões de escala e os plantões de um lote de escala. */
@Service
public class EscalaService {

	/** Versão de escala criada para um setor numa importação. */
	public record VersaoRegistrada(String setor, LocalDate competencia, int versao, int marcacoes) {
	}

	private final SetorRepository setores;
	private final EscalaRepository escalas;
	private final PlantaoRepository plantoes;
	private final ColaboradorResolver colaboradores;

	public EscalaService(SetorRepository setores, EscalaRepository escalas, PlantaoRepository plantoes,
			ColaboradorResolver colaboradores) {
		this.setores = setores;
		this.escalas = escalas;
		this.plantoes = plantoes;
		this.colaboradores = colaboradores;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public List<VersaoRegistrada> registrar(UUID importacaoId, LoteImportacao lote) {
		var competencia = lote.getPeriodoInicio().withDayOfMonth(1);
		var registradas = new ArrayList<VersaoRegistrada>();

		for (var setorLote : lote.getSetores()) {
			var setor = setores.obterOuCriar(setorLote.getNome());
			var escala = novaVersao(setor, competencia, importacaoId);

			var novos = new ArrayList<Plantao>();
			for (var colaboradorLote : setorLote.getColaboradores()) {
				var colaborador = colaboradores.resolver(DadosColaborador.de(colaboradorLote), false);
				boolean folguista = Boolean.TRUE.equals(colaboradorLote.getFolguista());
				for (var marcacao : colaboradorLote.getPlantoes()) {
					novos.add(new Plantao(escala, colaborador, folguista, marcacao));
				}
			}
			plantoes.saveAll(novos);
			registradas.add(new VersaoRegistrada(setor.getNome(), competencia, escala.getVersao(), novos.size()));
		}
		return registradas;
	}

	/**
	 * A versão vigente anterior deixa de valer antes de inserir a nova: o
	 * índice único parcial (uma vigente por setor + competência) é verificado
	 * a cada comando, e o Hibernate faria o INSERT antes do UPDATE.
	 */
	private Escala novaVersao(Setor setor, LocalDate competencia, UUID importacaoId) {
		escalas.findBySetorAndCompetenciaAndVigenteTrue(setor, competencia).ifPresent(anterior -> {
			anterior.substituir();
			escalas.saveAndFlush(anterior);
		});
		int versao = escalas.ultimaVersao(setor, competencia) + 1;
		return escalas.save(new Escala(setor, competencia, versao, importacaoId));
	}
}
