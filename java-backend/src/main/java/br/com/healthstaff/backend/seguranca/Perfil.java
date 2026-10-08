package br.com.healthstaff.backend.seguranca;

/**
 * Perfis de acesso. Leitura (GET) vale para todos; importar arquivos e
 * reprocessar análises é só de {@link #ADMIN} e {@link #GESTOR_RH}.
 */
public enum Perfil {

	ADMIN,
	GESTOR_RH,
	/** Somente leitura. */
	AUDITOR;

	/** Autoridade no Spring Security ({@code hasRole("ADMIN")} confere "ROLE_ADMIN"). */
	public String autoridade() {
		return "ROLE_" + name();
	}
}
