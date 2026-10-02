package br.com.healthstaff.backend.escala;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlantaoRepository extends JpaRepository<Plantao, Long> {

	List<Plantao> findByEscalaOrderByColaboradorIdAscDataAsc(Escala escala);

	long countByEscala(Escala escala);

	/** Plantões com o setor já carregado (exibição de alertas). */
	@Query("select p from Plantao p join fetch p.setor where p.id in :ids")
	List<Plantao> findComSetorByIdIn(Collection<Long> ids);
}
