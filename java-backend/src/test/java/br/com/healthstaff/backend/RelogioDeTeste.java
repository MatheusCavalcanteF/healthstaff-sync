package br.com.healthstaff.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Relógio que só anda quando o teste manda (inclusive para trás). */
public final class RelogioDeTeste extends Clock {

	private final AtomicReference<Instant> agora;

	public RelogioDeTeste(Instant inicio) {
		this.agora = new AtomicReference<>(inicio);
	}

	public void avancar(Duration tempo) {
		agora.updateAndGet(instante -> instante.plus(tempo));
	}

	@Override
	public Instant instant() {
		return agora.get();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zona) {
		throw new UnsupportedOperationException("relógio de teste é sempre UTC");
	}
}
