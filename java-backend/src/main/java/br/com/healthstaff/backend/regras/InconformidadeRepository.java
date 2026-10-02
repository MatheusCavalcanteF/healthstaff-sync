package br.com.healthstaff.backend.regras;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface InconformidadeRepository extends JpaRepository<Inconformidade, Long> {

	List<Inconformidade> findByAnaliseIdOrderByDataAscColaboradorIdAscIdAsc(Long analiseId);
}
