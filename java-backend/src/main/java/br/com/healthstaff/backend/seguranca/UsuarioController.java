package br.com.healthstaff.backend.seguranca;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Gestão de usuários. Todas as rotas são só de ADMIN, regra aplicada no
 * {@link SecurityConfig} (um ponto só para as regras de acesso por rota).
 * Não há exclusão: desativar preserva o histórico e revoga o acesso.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
class UsuarioController {

	private final UsuarioService servico;

	UsuarioController(UsuarioService servico) {
		this.servico = servico;
	}

	/** Todos, ativos e inativos, ordenados por nome. */
	@GetMapping
	List<UsuarioResponse> listar() {
		return servico.listar();
	}

	@GetMapping("/{id}")
	UsuarioResponse buscar(@PathVariable UUID id) {
		return servico.buscar(id);
	}

	@PostMapping
	ResponseEntity<UsuarioResponse> criar(@Valid @RequestBody NovoUsuarioRequest dados, UriComponentsBuilder uri) {
		var criado = servico.criar(dados);
		var local = uri.path("/api/v1/usuarios/{id}").buildAndExpand(criado.id()).toUri();
		return ResponseEntity.created(local).body(criado);
	}

	@PutMapping("/{id}")
	UsuarioResponse atualizar(@PathVariable UUID id, @Valid @RequestBody AtualizarUsuarioRequest dados,
			@AuthenticationPrincipal Usuario autor) {
		return servico.atualizar(id, dados, autor);
	}

	@PatchMapping("/{id}/status")
	UsuarioResponse alterarStatus(@PathVariable UUID id, @Valid @RequestBody StatusUsuarioRequest status,
			@AuthenticationPrincipal Usuario autor) {
		return servico.alterarStatus(id, status.ativo(), autor);
	}
}
