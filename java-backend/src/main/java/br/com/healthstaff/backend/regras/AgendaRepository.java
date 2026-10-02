package br.com.healthstaff.backend.regras;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import br.com.healthstaff.backend.escala.Plantao;

/** Leituras das escalas vigentes e dos saldos, no formato que as regras usam (somente leitura). */
interface AgendaRepository extends Repository<Plantao, Long> {

	@Query("""
			select new br.com.healthstaff.backend.regras.PlantaoAgendado(
			    p.id, p.colaborador.id, p.setor.id, p.data, p.inicio, p.fim)
			from Plantao p
			where p.escala.vigente = true and p.tipo = 'PLANTAO'
			  and p.inicio >= :de and p.inicio < :ate""")
	List<PlantaoAgendado> plantoesVigentes(LocalDateTime de, LocalDateTime ate);

	@Query("""
			select new br.com.healthstaff.backend.regras.MarcacaoDia(
			    p.colaborador.id, p.data, p.tipo, p.codigoOriginal, p.observacao, p.inicio, p.fim)
			from Plantao p
			where p.escala.vigente = true and p.data between :de and :ate""")
	List<MarcacaoDia> marcacoesVigentes(LocalDate de, LocalDate ate);

	/** Saldo mais recente de cada colaborador até a data (um retrato por relatório do Two). */
	@Query(nativeQuery = true, value = """
			select distinct on (colaborador_id)
			       colaborador_id as colaboradorId, minutos, data_referencia as dataReferencia
			from saldo_banco_horas
			where data_referencia <= :ate
			order by colaborador_id, data_referencia desc, id desc""")
	List<SaldoMaisRecente> saldosMaisRecentes(LocalDate ate);

	interface SaldoMaisRecente {

		Long getColaboradorId();

		Integer getMinutos();

		LocalDate getDataReferencia();
	}
}
