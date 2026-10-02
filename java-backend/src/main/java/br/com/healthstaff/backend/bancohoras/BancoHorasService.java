package br.com.healthstaff.backend.bancohoras;

import java.util.ArrayList;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.colaborador.ColaboradorResolver;
import br.com.healthstaff.backend.colaborador.DadosColaborador;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;

/** Registra os saldos de um lote do relatório de Banco de Horas do Two. */
@Service
public class BancoHorasService {

	private final SaldoBancoHorasRepository saldos;
	private final ColaboradorResolver colaboradores;

	public BancoHorasService(SaldoBancoHorasRepository saldos, ColaboradorResolver colaboradores) {
		this.saldos = saldos;
		this.colaboradores = colaboradores;
	}

	/** @return quantidade de saldos registrados */
	@Transactional(propagation = Propagation.MANDATORY)
	public int registrar(UUID importacaoId, LoteImportacao lote) {
		var novos = new ArrayList<SaldoBancoHoras>();
		for (var setor : lote.getSetores()) {
			for (var colaboradorLote : setor.getColaboradores()) {
				// O Two é a fonte oficial do nome e da matrícula.
				var colaborador = colaboradores.resolver(DadosColaborador.de(colaboradorLote), true);
				var saldo = colaboradorLote.getSaldoBancoHoras();
				if (saldo == null) {
					continue; // saldo ilegível no arquivo: já veio como inconsistência
				}
				var referencia = saldo.getDataReferencia() != null ? saldo.getDataReferencia() : lote.getPeriodoFim();
				novos.add(new SaldoBancoHoras(importacaoId, colaborador, saldo.getMinutos(), referencia,
						saldo.getInicioApuracao(), setor.getNome()));
			}
		}
		saldos.saveAll(novos);
		return novos.size();
	}
}
