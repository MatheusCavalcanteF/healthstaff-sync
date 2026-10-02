package br.com.healthstaff.backend;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes que sobem o contexto Spring: Postgres 18 real, embutido
 * (sem Docker), iniciado uma vez por execução e compartilhado. O Flyway
 * aplica as migrações ao subir o contexto, e o Hibernate valida as entidades
 * contra o esquema resultante.
 */
public abstract class IntegracaoComBanco {

	private static final EmbeddedPostgres POSTGRES = iniciar();

	private static EmbeddedPostgres iniciar() {
		try {
			var postgres = EmbeddedPostgres.builder().start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					postgres.close();
				}
				catch (IOException e) {
					// encerrando a JVM: nada a fazer
				}
			}));
			return postgres;
		}
		catch (IOException e) {
			throw new UncheckedIOException("não foi possível iniciar o Postgres embutido", e);
		}
	}

	@DynamicPropertySource
	static void banco(DynamicPropertyRegistry registro) {
		registro.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
		registro.add("spring.datasource.username", () -> "postgres");
		registro.add("spring.datasource.password", () -> "postgres");
	}
}
