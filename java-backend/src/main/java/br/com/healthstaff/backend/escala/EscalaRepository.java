package br.com.healthstaff.backend.escala;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EscalaRepository extends JpaRepository<Escala, Long> {

	Optional<Escala> findBySetorAndCompetenciaAndVigenteTrue(Setor setor, LocalDate competencia);

	@Query("select coalesce(max(e.versao), 0) from Escala e where e.setor = :setor and e.competencia = :competencia")
	int ultimaVersao(Setor setor, LocalDate competencia);

	List<Escala> findBySetorAndCompetenciaOrderByVersao(Setor setor, LocalDate competencia);
}
