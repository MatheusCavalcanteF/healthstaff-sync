package br.com.healthstaff.backend.colaborador;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ColaboradorRepository extends JpaRepository<Colaborador, Long> {

	Optional<Colaborador> findByMatricula(String matricula);

	/** Cadastro conhecido, enviado ao extrator para associar nomes da escala. */
	List<Colaborador> findByMatriculaIsNotNullOrderByMatricula();

	Optional<Colaborador> findByMatriculaIsNullAndNomeNormalizado(String nomeNormalizado);

	List<Colaborador> findByMatriculaIsNotNullAndNomeNormalizado(String nomeNormalizado);
}
