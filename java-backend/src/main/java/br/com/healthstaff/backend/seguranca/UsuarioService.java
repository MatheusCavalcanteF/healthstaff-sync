package br.com.healthstaff.backend.seguranca;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.web.ParametroInvalidoException;

/**
 * Cadastro de usuários (só ADMIN chega aqui; ver {@link SecurityConfig}).
 *
 * <p>Regras:
 * <ul>
 * <li>E-mail único, sem diferenciar maiúsculas: 409 {@code EMAIL_JA_CADASTRADO}.</li>
 * <li>Sempre resta ao menos um ADMIN ativo, seja por desativação ou por troca de
 * perfil: 409 {@code ULTIMO_ADMIN}. Os admins ativos ficam travados durante a
 * operação, então dois admins se desativando ao mesmo tempo não zeram o sistema.</li>
 * <li>Ninguém desativa a própria conta nem tira o próprio perfil de ADMIN: 409
 * {@code ALTERACAO_PROPRIA_BLOQUEADA}.</li>
 * <li>Trocar senha, e-mail ou perfil, ou desativar, derruba as sessões abertas
 * do usuário (ver {@link Usuario#getVersaoCredenciais()}).</li>
 * </ul>
 */
@Service
public class UsuarioService {

	private final UsuarioRepository usuarios;
	private final PasswordEncoder senhas;

	UsuarioService(UsuarioRepository usuarios, PasswordEncoder senhas) {
		this.usuarios = usuarios;
		this.senhas = senhas;
	}

	@Transactional(readOnly = true)
	public List<UsuarioResponse> listar() {
		return usuarios.findAllByOrderByNomeAsc().stream().map(UsuarioResponse::de).toList();
	}

	@Transactional(readOnly = true)
	public UsuarioResponse buscar(UUID id) {
		return UsuarioResponse.de(usuario(id));
	}

	@Transactional
	public UsuarioResponse criar(NovoUsuarioRequest dados) {
		exigirSenhaValida(dados.senha());
		var email = Usuario.normalizarEmail(dados.email());
		if (usuarios.findByEmail(email).isPresent()) {
			throw ConflitoUsuarioException.emailJaCadastrado(email);
		}
		var usuario = Usuario.novo(dados.nome(), email, senhas.encode(dados.senha()), dados.perfil());
		return UsuarioResponse.de(gravar(usuario));
	}

	/** @param autor quem faz a alteração (o ADMIN logado) */
	@Transactional
	public UsuarioResponse atualizar(UUID id, AtualizarUsuarioRequest dados, Usuario autor) {
		if (dados.trocaSenha()) {
			exigirSenhaValida(dados.senha());
		}
		var adminsAtivos = usuarios.travarAdminsAtivos();
		var usuario = usuario(id);

		if (usuario.ehAdminAtivo() && dados.perfil() != Perfil.ADMIN) {
			exigirQueNaoSejaOProprio(usuario, autor);
			exigirOutroAdminAtivo(adminsAtivos, usuario);
		}
		var email = Usuario.normalizarEmail(dados.email());
		if (!email.equals(usuario.getEmail()) && usuarios.findByEmail(email).isPresent()) {
			throw ConflitoUsuarioException.emailJaCadastrado(email);
		}

		usuario.atualizar(dados.nome(), email, dados.perfil());
		if (dados.trocaSenha()) {
			usuario.trocarSenha(senhas.encode(dados.senha()));
		}
		return UsuarioResponse.de(gravar(usuario));
	}

	/** @param autor quem faz a alteração (o ADMIN logado) */
	@Transactional
	public UsuarioResponse alterarStatus(UUID id, boolean ativo, Usuario autor) {
		var adminsAtivos = usuarios.travarAdminsAtivos();
		var usuario = usuario(id);
		if (ativo) {
			usuario.ativar();
		}
		else if (usuario.isAtivo()) {
			exigirQueNaoSejaOProprio(usuario, autor);
			if (usuario.ehAdminAtivo()) {
				exigirOutroAdminAtivo(adminsAtivos, usuario);
			}
			usuario.desativar();
		}
		return UsuarioResponse.de(gravar(usuario));
	}

	private Usuario usuario(UUID id) {
		return usuarios.findById(id).orElseThrow(() -> new UsuarioNaoEncontradoException(id));
	}

	private static void exigirSenhaValida(String senha) {
		PoliticaSenha.problema(senha).ifPresent(problema -> {
			throw new ParametroInvalidoException(problema);
		});
	}

	private static void exigirQueNaoSejaOProprio(Usuario usuario, Usuario autor) {
		if (usuario.getId().equals(autor.getId())) {
			throw ConflitoUsuarioException.alteracaoPropria();
		}
	}

	private static void exigirOutroAdminAtivo(List<Usuario> adminsAtivos, Usuario usuario) {
		if (adminsAtivos.stream().noneMatch(admin -> !admin.getId().equals(usuario.getId()))) {
			throw ConflitoUsuarioException.ultimoAdmin();
		}
	}

	/** O unique do banco é a palavra final: cobre dois cadastros simultâneos do mesmo e-mail. */
	private Usuario gravar(Usuario usuario) {
		try {
			return usuarios.saveAndFlush(usuario);
		}
		catch (DataIntegrityViolationException e) {
			throw ConflitoUsuarioException.emailJaCadastrado(usuario.getEmail());
		}
	}
}
