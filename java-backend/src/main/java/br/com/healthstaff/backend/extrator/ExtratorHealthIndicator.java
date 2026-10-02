package br.com.healthstaff.backend.extrator;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exibe o estado do extrator em {@code /actuator/health} (componente "extrator"). */
@Component("extrator")
class ExtratorHealthIndicator implements HealthIndicator {

	private final ExtratorClient cliente;

	ExtratorHealthIndicator(ExtratorClient cliente) {
		this.cliente = cliente;
	}

	@Override
	public Health health() {
		try {
			var saude = cliente.saude();
			var construtor = "ok".equals(saude.get("status")) ? Health.up() : Health.down();
			return construtor.withDetails(saude).build();
		}
		catch (ExtratorIndisponivelException e) {
			return Health.down().withDetail("erro", e.getMessage()).build();
		}
	}
}
