package br.com.healthstaff.backend.extrator;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.RelogioDeTeste;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Cache e consulta única do indicador, sem Spring: o cliente é um mock que
 * fica "travado" até o teste liberar, para as corridas serem determinísticas.
 */
class ExtratorHealthIndicatorTest {

	static final Map<String, Object> NO_AR = Map.of("status", "ok", "versao", "1.0");

	final ExtratorClient cliente = mock(ExtratorClient.class);
	final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-13T12:00:00Z"));
	final ExtratorHealthIndicator indicador = new ExtratorHealthIndicator(cliente, relogio);
	final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void encerrar() {
		threads.shutdownNow();
	}

	Future<Health> emParalelo() {
		return threads.submit(() -> indicador.health());
	}

	/** Próximas consultas ficam presas até {@code liberar}; {@code entrou} avisa que uma começou. */
	void travarExtrator(CountDownLatch entrou, CountDownLatch liberar, Map<String, Object> resposta) {
		doAnswer(invocacao -> {
			entrou.countDown();
			liberar.await();
			if (resposta == null) {
				throw new ExtratorIndisponivelException("extrator inacessível: timeout");
			}
			return resposta;
		}).when(cliente).saude();
	}

	@Test
	void resultadoValeDezSegundos() {
		doReturn(NO_AR).when(cliente).saude();

		indicador.health();
		relogio.avancar(ExtratorHealthIndicator.VALIDADE.minusMillis(1));
		indicador.health();
		verify(cliente, times(1)).saude();

		relogio.avancar(Duration.ofMillis(1));
		indicador.health();
		verify(cliente, times(2)).saude();
	}

	@Test
	void relogioQueVoltaNaoCongelaOResultado() {
		doReturn(NO_AR).when(cliente).saude();

		indicador.health();
		relogio.avancar(Duration.ofHours(-1));
		indicador.health();

		verify(cliente, times(2)).saude();
	}

	@Test
	void vencidoComConsultaEmAndamentoAsDemaisRecebemOResultadoAnteriorSemEsperar() throws Exception {
		doReturn(NO_AR).when(cliente).saude();
		indicador.health();
		relogio.avancar(Duration.ofSeconds(11));
		var entrou = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		travarExtrator(entrou, liberar, null);

		var quemConsulta = emParalelo();
		assertThat(entrou.await(5, SECONDS)).isTrue();
		var demais = IntStream.range(0, 19).mapToObj(i -> emParalelo()).toList();

		// Respondem com o extrator ainda travado: o último resultado, sem nova chamada.
		for (var resposta : demais) {
			assertThat(resposta.get(5, SECONDS).getStatus()).isEqualTo(Status.UP);
		}
		liberar.countDown();
		assertThat(quemConsulta.get(5, SECONDS).getStatus()).isEqualTo(Status.DOWN);
		verify(cliente, times(2)).saude();
	}

	@Test
	void naPrimeiraConsultaAsSimultaneasEsperamAMesmaChamada() throws Exception {
		var entrou = new CountDownLatch(1);
		var liberar = new CountDownLatch(1);
		travarExtrator(entrou, liberar, NO_AR);

		var respostas = IntStream.range(0, 20).mapToObj(i -> emParalelo()).toList();
		assertThat(entrou.await(5, SECONDS)).isTrue();
		// As outras 19 ficam na fila da trava enquanto a consulta não termina.
		var prazo = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (indicador.consultasEsperando() < 19) {
			assertThat(System.nanoTime()).as("19 requisições na fila em até 5s").isLessThan(prazo);
			Thread.sleep(5);
		}

		// Sem resultado anterior, nenhuma responde antes da consulta em andamento.
		assertThat(respostas).noneMatch(Future::isDone);
		liberar.countDown();
		for (var resposta : respostas) {
			assertThat(resposta.get(5, SECONDS).getStatus()).isEqualTo(Status.UP);
		}
		verify(cliente, times(1)).saude();
	}
}
