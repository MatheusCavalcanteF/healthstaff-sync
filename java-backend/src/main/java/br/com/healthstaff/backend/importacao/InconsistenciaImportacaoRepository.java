package br.com.healthstaff.backend.importacao;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InconsistenciaImportacaoRepository extends JpaRepository<InconsistenciaImportacao, Long> {

	List<InconsistenciaImportacao> findByImportacaoIdOrderById(UUID importacaoId);
}
