package br.com.healthstaff.backend.seguranca;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

	/** @param email já normalizado ({@link Usuario#normalizarEmail}) */
	Optional<Usuario> findByEmail(String email);

	boolean existsByPerfilAndAtivoTrue(Perfil perfil);

	List<Usuario> findAllByOrderByNomeAsc();

	/**
	 * Os ADMIN ativos, travados ({@code select ... for update}) até o fim da
	 * transação: duas desativações simultâneas não deixam o sistema sem admin,
	 * porque a segunda espera a primeira e já enxerga o resultado dela.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from Usuario u where u.perfil = br.com.healthstaff.backend.seguranca.Perfil.ADMIN and u.ativo = true")
	List<Usuario> travarAdminsAtivos();
}
