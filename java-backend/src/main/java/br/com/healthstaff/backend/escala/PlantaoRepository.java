package br.com.healthstaff.backend.escala;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantaoRepository extends JpaRepository<Plantao, Long> {

	List<Plantao> findByEscalaOrderByColaboradorIdAscDataAsc(Escala escala);

	long countByEscala(Escala escala);
}
