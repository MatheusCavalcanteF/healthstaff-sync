package br.com.healthstaff.backend.seguranca;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Autenticação da API ({@code healthstaff.security.*}). */
@ConfigurationProperties("healthstaff.security")
public record SegurancaProperties(@DefaultValue Jwt jwt, @DefaultValue AdminInicial adminInicial) {

	/** HS256 exige chave de pelo menos 256 bits. */
	static final int TAMANHO_MINIMO_SEGREDO = 32;

	/**
	 * @param secret    chave HMAC (HEALTHSTAFF_JWT_SECRET). Obrigatória e sem valor padrão:
	 *                  um segredo conhecido permitiria forjar tokens de qualquer perfil
	 * @param expiracao validade do token; sem renovação, o usuário faz login de novo
	 */
	public record Jwt(String secret, @DefaultValue("8h") Duration expiracao) {

		public Jwt {
			if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < TAMANHO_MINIMO_SEGREDO) {
				throw new IllegalArgumentException("defina healthstaff.security.jwt.secret (HEALTHSTAFF_JWT_SECRET) com "
						+ "pelo menos " + TAMANHO_MINIMO_SEGREDO + " bytes; gere com: openssl rand -base64 48");
			}
			if (expiracao.isNegative() || expiracao.isZero()) {
				throw new IllegalArgumentException("healthstaff.security.jwt.expiracao deve ser positiva");
			}
		}

		byte[] chave() {
			return secret.getBytes(StandardCharsets.UTF_8);
		}
	}

	/**
	 * Primeiro administrador, criado na subida só se não houver nenhum ADMIN ativo
	 * (ver {@link CriacaoAdminInicial}). Depois disso estas variáveis são ignoradas.
	 */
	public record AdminInicial(@DefaultValue("Administrador") String nome, String email, String senha) {

		@Override
		public String toString() {
			return "AdminInicial[nome=" + nome + ", email=" + email + "]";
		}
	}
}
