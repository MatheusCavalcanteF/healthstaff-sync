package br.com.healthstaff.backend.seguranca;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

	/** @param email já normalizado ({@link Usuario#normalizarEmail}) */
	Optional<Usuario> findByEmail(String email);

	boolean existsByPerfilAndAtivoTrue(Perfil perfil);
}
