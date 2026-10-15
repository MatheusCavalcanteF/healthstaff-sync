package br.com.healthstaff.backend.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.RelogioDeTeste;

/** Contagem e bloqueio progressivo do login, sem Spring e com relógio controlável. */
class LimiteTentativasLoginTest {

	static final String EMAIL = "gestor@hospital.local";

	final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-15T12:00:00Z"));
	final LimiteTentativasLogin limite = new LimiteTentativasLogin(
			new SegurancaProperties.Login(5, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(1)),
			relogio);

	void tentar(int vezes) {
		for (int i = 0; i < vezes; i++) {
			limite.registrarTentativa(EMAIL);
		}
	}

	long bloqueioEmSegundos() {
		try {
			limite.registrarTentativa(EMAIL);
		}
		catch (TentativasExcedidasException e) {
			return e.getSegundosParaNovaTentativa();
		}
		throw new AssertionError("esperava o e-mail bloqueado");
	}

	@Test
	void cincoTentativasPassamEASextaEsperaUmMinuto() {
		tentar(5);

		assertThat(bloqueioEmSegundos()).isEqualTo(60);
		relogio.avancar(Duration.ofSeconds(59));
		assertThat(bloqueioEmSegundos()).isEqualTo(1);
	}

	@Test
	void cadaNovaTentativaErradaDobraOBloqueioAteOTeto() {
		tentar(5);
		var esperados = new long[] { 60, 120, 240, 480, 900, 900 };
		for (var esperado : esperados) {
			assertThat(bloqueioEmSegundos()).isEqualTo(esperado);
			relogio.avancar(Duration.ofSeconds(esperado));
			tentar(1); // a tentativa liberada também erra
		}
	}

	@Test
	void acertoZeraAContagem() {
		tentar(4);
		limite.registrarAcerto(EMAIL);

		assertThatCode(() -> tentar(4)).doesNotThrowAnyException();
	}

	@Test
	void acertoDuranteOBloqueioNaoEhPossivel() {
		tentar(5);

		// A tentativa é recusada antes de conferir a senha: certa ou errada, 429.
		assertThatThrownBy(() -> limite.registrarTentativa(EMAIL)).isInstanceOf(TentativasExcedidasException.class);
	}

	@Test
	void emailComMaiusculasEEspacosEhOMesmo() {
		tentar(5);

		assertThatThrownBy(() -> limite.registrarTentativa("  Gestor@Hospital.LOCAL "))
				.isInstanceOf(TentativasExcedidasException.class);
	}

	@Test
	void outroEmailNaoEhAfetado() {
		tentar(5);

		assertThatCode(() -> limite.registrarTentativa("outra@hospital.local")).doesNotThrowAnyException();
	}

	@Test
	void semTentativasPorUmaHoraAContagemZera() {
		tentar(4);
		relogio.avancar(Duration.ofHours(1));

		assertThatCode(() -> tentar(4)).doesNotThrowAnyException();
	}

	@Test
	void mensagemEmMinutosArredondaParaCima() {
		tentar(5);
		relogio.avancar(Duration.ofMinutes(1));
		tentar(1); // bloqueio de 2 min

		assertThatThrownBy(() -> limite.registrarTentativa(EMAIL))
				.hasMessage("muitas tentativas de login; tente de novo em 2 min");
	}

	@Test
	void tentativasSimultaneasNaoPassamDoLimite() throws Exception {
		var aceitas = new AtomicInteger();
		var largada = new CountDownLatch(1);
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 50; i++) {
				threads.submit(() -> {
					largada.await();
					try {
						limite.registrarTentativa(EMAIL);
						aceitas.incrementAndGet();
					}
					catch (TentativasExcedidasException e) {
						// recusada: esperado para as que chegam depois do limite
					}
					return null;
				});
			}
			largada.countDown();
			threads.shutdown();
			assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		// Contar antes de conferir a senha impede que 50 em paralelo passem todas.
		assertThat(aceitas).hasValue(5);
	}
}
