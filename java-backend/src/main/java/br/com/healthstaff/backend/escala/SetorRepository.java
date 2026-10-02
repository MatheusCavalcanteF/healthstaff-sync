package br.com.healthstaff.backend.escala;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SetorRepository extends JpaRepository<Setor, Long> {

	Optional<Setor> findByChave(String chave);

	default Setor obterOuCriar(String nome) {
		return findByChave(Setor.chave(nome)).orElseGet(() -> save(new Setor(nome)));
	}
}
