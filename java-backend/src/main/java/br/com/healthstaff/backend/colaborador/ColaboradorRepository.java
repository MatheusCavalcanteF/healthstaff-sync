package br.com.healthstaff.backend.colaborador;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ColaboradorRepository extends JpaRepository<Colaborador, Long>, JpaSpecificationExecutor<Colaborador> {

	Optional<Colaborador> findByMatricula(String matricula);

	/** Cadastro conhecido, enviado ao extrator para associar nomes da escala. */
	List<Colaborador> findByMatriculaIsNotNullOrderByMatricula();

	Optional<Colaborador> findByMatriculaIsNullAndNomeNormalizado(String nomeNormalizado);

	List<Colaborador> findByMatriculaIsNotNullAndNomeNormalizado(String nomeNormalizado);

	/** Saldo mais recente do Two de cada colaborador informado (um retrato por relatório). */
	@Query(nativeQuery = true, value = """
			select distinct on (colaborador_id)
			       colaborador_id as colaboradorId, minutos, data_referencia as dataReferencia
			from saldo_banco_horas
			where colaborador_id in (:ids)
			order by colaborador_id, data_referencia desc, id desc""")
	List<SaldoAtual> saldosAtuais(Collection<Long> ids);

	interface SaldoAtual {

		Long getColaboradorId();

		Integer getMinutos();

		LocalDate getDataReferencia();
	}
}
