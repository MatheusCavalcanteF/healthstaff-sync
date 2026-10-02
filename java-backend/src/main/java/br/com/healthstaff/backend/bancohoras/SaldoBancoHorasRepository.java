package br.com.healthstaff.backend.bancohoras;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.healthstaff.backend.colaborador.Colaborador;

public interface SaldoBancoHorasRepository extends JpaRepository<SaldoBancoHoras, Long> {

	List<SaldoBancoHoras> findByImportacaoId(UUID importacaoId);

	/** Saldo mais recente do colaborador (pela data de referência). */
	Optional<SaldoBancoHoras> findFirstByColaboradorOrderByDataReferenciaDescIdDesc(Colaborador colaborador);
}
