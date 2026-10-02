package br.com.healthstaff.backend.regras;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface ConsolidacaoHorasRepository extends JpaRepository<ConsolidacaoHoras, Long> {

	List<ConsolidacaoHoras> findByAnaliseIdOrderByColaboradorId(Long analiseId);
}
