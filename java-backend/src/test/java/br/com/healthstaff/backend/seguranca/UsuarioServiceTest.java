package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A regra do último ADMIN. Pela API ela só é alcançável em corrida: quem chama
 * é sempre um ADMIN ativo e não pode mexer em si mesmo, então o alvo nunca é o
 * único. Aqui o serviço é chamado direto, inclusive em paralelo.
 */
@SpringBootTest
class UsuarioServiceTest extends IntegracaoComBanco {

	@Autowired
	UsuarioService servico;

	@Autowired
	UsuarioRepository usuarios;

	@Autowired
	JdbcTemplate jdbc;

	/** Autor sem cadastro (não é nenhum dos alvos): isola a regra do último ADMIN. */
	final Usuario autorExterno = Usuario.novo("Externo", "externo@teste.local", "hash", Perfil.ADMIN);

	@BeforeEach
	void limpar() {
		jdbc.update("delete from usuario");
	}

	int adminsAtivos() {
		return jdbc.queryForObject("select count(*) from usuario where perfil = 'ADMIN' and ativo", Integer.class);
	}

	@Test
	void naoDesativaOUltimoAdmin() {
		var unico = UsuariosDeTeste.obter(usuarios, "admin@teste.local", Perfil.ADMIN);

		assertThatThrownBy(() -> servico.alterarStatus(unico.getId(), false, autorExterno))
				.isInstanceOfSatisfying(ConflitoUsuarioException.class,
						e -> assertThat(e.getCodigo()).isEqualTo("ULTIMO_ADMIN"));
		assertThat(adminsAtivos()).isEqualTo(1);
	}

	@Test
	void naoRebaixaOUltimoAdmin() {
		var unico = UsuariosDeTeste.obter(usuarios, "admin@teste.local", Perfil.ADMIN);
		var dados = new AtualizarUsuarioRequest("Admin", "admin@teste.local", Perfil.GESTOR_RH, null);

		assertThatThrownBy(() -> servico.atualizar(unico.getId(), dados, autorExterno))
				.isInstanceOfSatisfying(ConflitoUsuarioException.class,
						e -> assertThat(e.getCodigo()).isEqualTo("ULTIMO_ADMIN"));
		assertThat(adminsAtivos()).isEqualTo(1);
	}

	@Test
	void adminInativoNaoContaComoOutroAdmin() {
		var ativo = UsuariosDeTeste.obter(usuarios, "admin@teste.local", Perfil.ADMIN);
		var inativo = UsuariosDeTeste.obter(usuarios, "admin2@teste.local", Perfil.ADMIN);
		inativo.desativar();
		usuarios.save(inativo);

		assertThatThrownBy(() -> servico.alterarStatus(ativo.getId(), false, autorExterno))
				.isInstanceOf(ConflitoUsuarioException.class);
	}

	/**
	 * Dois admins desativam um ao outro ao mesmo tempo. Sem a trava, cada um veria
	 * "há outro admin ativo" e os dois seriam desativados.
	 */
	@RepeatedTest(5)
	void doisAdminsSeDesativandoAoMesmoTempoNaoZeramOsAdmins() throws Exception {
		var a = UsuariosDeTeste.obter(usuarios, "admin-a@teste.local", Perfil.ADMIN);
		var b = UsuariosDeTeste.obter(usuarios, "admin-b@teste.local", Perfil.ADMIN);
		var largada = new CountDownLatch(1);
		Callable<Object> aDesativaB = () -> {
			largada.await();
			return servico.alterarStatus(b.getId(), false, a);
		};
		Callable<Object> bDesativaA = () -> {
			largada.await();
			return servico.alterarStatus(a.getId(), false, b);
		};

		var resultados = new ArrayList<Future<Object>>();
		try (var executor = Executors.newFixedThreadPool(2)) {
			resultados.add(executor.submit(aDesativaB));
			resultados.add(executor.submit(bDesativaA));
			largada.countDown();

			int sucessos = 0;
			int recusas = 0;
			for (var resultado : resultados) {
				try {
					resultado.get();
					sucessos++;
				}
				catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOfSatisfying(ConflitoUsuarioException.class,
							c -> assertThat(c.getCodigo()).isEqualTo("ULTIMO_ADMIN"));
					recusas++;
				}
			}
			assertThat(sucessos).isEqualTo(1);
			assertThat(recusas).isEqualTo(1);
		}
		assertThat(adminsAtivos()).isEqualTo(1);
	}
}
