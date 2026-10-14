package br.com.healthstaff.backend.extrator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exibe o estado do extrator em {@code /actuator/health} (componente "extrator", só para ADMIN).
 *
 * <p>O health é livre, então cada GET chamaria o extrator. O último resultado vale
 * {@link #VALIDADE} para qualquer requisição (anônima ou não, com ou sem parâmetros
 * na URL), e só uma consulta ao extrator roda por vez: vencido o resultado, a primeira
 * requisição consulta e as simultâneas recebem o resultado anterior, sem esperar. Só na
 * primeira consulta (não há resultado anterior) as demais aguardam a que está em
 * andamento, limitada pelo {@code timeout-saude} do cliente.
 */
@Component("extrator")
class ExtratorHealthIndicator implements HealthIndicator {

	static final Duration VALIDADE = Duration.ofSeconds(10);

	private static final Logger log = LoggerFactory.getLogger(ExtratorHealthIndicator.class);

	private final ExtratorClient cliente;
	private final Clock relogio;
	private final ReentrantLock consulta = new ReentrantLock();
	private volatile Resultado ultimo;

	/** Relógio do sistema, salvo se o contexto tiver um {@link Clock} (os testes usam um controlável). */
	@Autowired
	ExtratorHealthIndicator(ExtratorClient cliente, ObjectProvider<Clock> relogio) {
		this(cliente, relogio.getIfAvailable(Clock::systemUTC));
	}

	ExtratorHealthIndicator(ExtratorClient cliente, Clock relogio) {
		this.cliente = cliente;
		this.relogio = relogio;
	}

	@Override
	public Health health() {
		var atual = ultimo;
		if (vigente(atual)) {
			return atual.saude();
		}
		if (atual == null) {
			consulta.lock();
		}
		else if (!consulta.tryLock()) {
			// Outra requisição já está consultando: devolve o último resultado em vez de chamar de novo.
			return atual.saude();
		}
		try {
			atual = ultimo;
			if (vigente(atual)) {
				return atual.saude(); // renovado enquanto esta requisição esperava a vez
			}
			var novo = new Resultado(consultar(), relogio.instant());
			ultimo = novo;
			return novo.saude();
		}
		finally {
			consulta.unlock();
		}
	}

	/** Só para testes: quantas requisições estão paradas esperando a consulta em andamento. */
	int consultasEsperando() {
		return consulta.getQueueLength();
	}

	/** Nunca lança: qualquer falha vira DOWN e entra no cache como qualquer outro resultado. */
	private Health consultar() {
		try {
			var saude = cliente.saude();
			var construtor = "ok".equals(saude.get("status")) ? Health.up() : Health.down();
			return construtor.withDetails(saude).build();
		}
		catch (ExtratorIndisponivelException e) {
			// Falha esperada (fora do ar, timeout...): a mensagem basta, sem stack trace a cada 10s.
			log.warn("healthcheck do extrator falhou: {}", e.getMessage());
		}
		catch (RuntimeException e) {
			// Inesperada: se escapasse, derrubaria o /actuator/health inteiro e nada ficaria no cache.
			log.warn("healthcheck do extrator falhou com erro inesperado", e);
		}
		// As mensagens podem trazer a URL interna do extrator: ficam no log, não na resposta.
		return Health.down().withDetail("erro", "extrator inacessível; detalhes no log do backend").build();
	}

	/** Relógio que voltou (ajuste de hora) conta como vencido: senão o resultado ficaria parado. */
	private boolean vigente(Resultado resultado) {
		if (resultado == null) {
			return false;
		}
		var idade = Duration.between(resultado.momento(), relogio.instant());
		return !idade.isNegative() && idade.compareTo(VALIDADE) < 0;
	}

	private record Resultado(Health saude, Instant momento) {
	}
}
