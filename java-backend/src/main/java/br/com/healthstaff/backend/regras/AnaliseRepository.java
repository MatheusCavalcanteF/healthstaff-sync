package br.com.healthstaff.backend.regras;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface AnaliseRepository extends JpaRepository<Analise, Long> {

	Optional<Analise> findByCompetenciaAndVigenteTrue(LocalDate competencia);
}
