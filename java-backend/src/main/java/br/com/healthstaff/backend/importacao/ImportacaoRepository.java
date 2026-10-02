package br.com.healthstaff.backend.importacao;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportacaoRepository extends JpaRepository<Importacao, UUID> {

	Optional<Importacao> findByTipoDocumentoAndHashSha256(String tipoDocumento, String hashSha256);
}
