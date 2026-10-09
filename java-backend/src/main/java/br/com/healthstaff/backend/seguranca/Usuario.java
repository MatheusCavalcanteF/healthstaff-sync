package br.com.healthstaff.backend.seguranca;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Usuário da aplicação. O login é o e-mail; a senha fica só como hash BCrypt. */
@Entity
@Table(name = "usuario")
public class Usuario implements UserDetails {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String nome;

	@Column(nullable = false)
	private String email;

	@Column(name = "senha_hash", nullable = false, length = 100)
	private String senhaHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Perfil perfil;

	@Column(nullable = false)
	private boolean ativo;

	@Column(name = "data_criacao", nullable = false)
	private Instant dataCriacao;

	/**
	 * Vai no token (claim "ver"); muda quando senha, e-mail ou perfil mudam ou a
	 * conta é desativada, e aí os tokens antigos deixam de valer.
	 */
	@Column(name = "versao_credenciais", nullable = false)
	private int versaoCredenciais;

	protected Usuario() {
	}

	/** @param senhaHash hash já calculado (nunca a senha em texto) */
	public static Usuario novo(String nome, String email, String senhaHash, Perfil perfil) {
		var usuario = new Usuario();
		usuario.id = UUID.randomUUID();
		usuario.nome = nome.strip();
		usuario.email = normalizarEmail(email);
		usuario.senhaHash = senhaHash;
		usuario.perfil = perfil;
		usuario.ativo = true;
		usuario.dataCriacao = Instant.now();
		return usuario;
	}

	/** E-mail como fica gravado e como é buscado no login (sem espaços, minúsculas). */
	public static String normalizarEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	/** Nome, e-mail e perfil; mudar e-mail ou perfil derruba as sessões abertas. */
	public void atualizar(String nome, String email, Perfil perfil) {
		var novoEmail = normalizarEmail(email);
		if (!novoEmail.equals(this.email) || perfil != this.perfil) {
			versaoCredenciais++;
		}
		this.nome = nome.strip();
		this.email = novoEmail;
		this.perfil = perfil;
	}

	/** @param senhaHash hash já calculado; derruba as sessões abertas */
	public void trocarSenha(String senhaHash) {
		this.senhaHash = senhaHash;
		versaoCredenciais++;
	}

	public void ativar() {
		ativo = true;
	}

	/** Derruba as sessões abertas, inclusive para quando a conta for reativada. */
	public void desativar() {
		if (ativo) {
			ativo = false;
			versaoCredenciais++;
		}
	}

	public boolean ehAdminAtivo() {
		return ativo && perfil == Perfil.ADMIN;
	}

	public UUID getId() {
		return id;
	}

	public String getNome() {
		return nome;
	}

	public String getEmail() {
		return email;
	}

	public Perfil getPerfil() {
		return perfil;
	}

	public boolean isAtivo() {
		return ativo;
	}

	public Instant getDataCriacao() {
		return dataCriacao;
	}

	public int getVersaoCredenciais() {
		return versaoCredenciais;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority(perfil.autoridade()));
	}

	@Override
	public String getPassword() {
		return senhaHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isEnabled() {
		return ativo;
	}
}
