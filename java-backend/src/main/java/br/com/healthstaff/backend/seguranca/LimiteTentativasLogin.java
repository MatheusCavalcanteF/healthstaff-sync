package br.com.healthstaff.backend.seguranca;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Freio contra força bruta no login, por e-mail (normalizado).
 *
 * <p>Cada tentativa conta <b>antes</b> de conferir a senha: assim, requisições
 * em paralelo não passam todas antes de a primeira falha ser registrada. Depois
 * de {@code tentativasLivres} tentativas sem acerto, o e-mail fica bloqueado por
 * {@code bloqueioInicial}, que dobra a cada nova tentativa errada até
 * {@code bloqueioMaximo}. Um acerto zera a contagem; sem tentativas por
 * {@code esquecerApos}, ela também zera.
 *
 * <p>Vale igual para e-mail sem conta (a resposta não revela quais existem).
 * Em memória: com mais de uma instância do backend, cada uma conta à parte.
 */
@Component
class LimiteTentativasLogin {

	/** Acima disso, os registros sem bloqueio vigente são descartados (memória limitada). */
	static final int MAXIMO_REGISTROS = 10_000;

	private static final Logger log = LoggerFactory.getLogger(LimiteTentativasLogin.class);

	private final SegurancaProperties.Login regras;
	private final Clock relogio;
	private final Map<String, Registro> registros = new ConcurrentHashMap<>();

	@Autowired
	LimiteTentativasLogin(SegurancaProperties propriedades, ObjectProvider<Clock> relogio) {
		this(propriedades.login(), relogio.getIfAvailable(Clock::systemUTC));
	}

	LimiteTentativasLogin(SegurancaProperties.Login regras, Clock relogio) {
		this.regras = regras;
		this.relogio = relogio;
	}

	/**
	 * Conta uma tentativa de login para o e-mail.
	 *
	 * @throws TentativasExcedidasException se o e-mail está bloqueado agora (a tentativa não conta)
	 */
	void registrarTentativa(String email) {
		var agora = relogio.instant();
		descartarExcesso(agora);
		registros.compute(chave(email), (chave, atual) -> {
			var registro = atual == null || atual.esquecido(agora, regras.esquecerApos()) ? Registro.VAZIO : atual;
			if (registro.bloqueadoEm(agora)) {
				throw new TentativasExcedidasException(Duration.between(agora, registro.bloqueadoAte()));
			}
			var tentativas = registro.tentativas() + 1;
			Instant bloqueadoAte = null;
			if (tentativas >= regras.tentativasLivres()) {
				var bloqueio = duracaoDoBloqueio(tentativas);
				bloqueadoAte = agora.plus(bloqueio);
				log.warn("login bloqueado por {} s após {} tentativas seguidas sem acerto",
						bloqueio.toSeconds(), tentativas);
			}
			return new Registro(tentativas, agora, bloqueadoAte);
		});
	}

	/** Login aceito: a contagem do e-mail zera. */
	void registrarAcerto(String email) {
		registros.remove(chave(email));
	}

	/** Só para testes que dividem o contexto Spring. */
	void esquecerTudo() {
		registros.clear();
	}

	private Duration duracaoDoBloqueio(int tentativas) {
		// Até 2^20 vezes o inicial: além disso o teto já valeu (e evita estouro no shift).
		var dobras = Math.min(tentativas - regras.tentativasLivres(), 20);
		var bloqueio = regras.bloqueioInicial().multipliedBy(1L << dobras);
		return bloqueio.compareTo(regras.bloqueioMaximo()) > 0 ? regras.bloqueioMaximo() : bloqueio;
	}

	/**
	 * E-mails inventados em massa não podem esgotar a memória: passando do limite,
	 * ficam só os bloqueios vigentes (cada um custou várias tentativas com BCrypt).
	 */
	private void descartarExcesso(Instant agora) {
		if (registros.size() > MAXIMO_REGISTROS) {
			registros.values().removeIf(registro -> !registro.bloqueadoEm(agora));
		}
	}

	private static String chave(String email) {
		return Usuario.normalizarEmail(email);
	}

	private record Registro(int tentativas, Instant ultimaTentativa, Instant bloqueadoAte) {

		static final Registro VAZIO = new Registro(0, Instant.MIN, null);

		boolean bloqueadoEm(Instant agora) {
			return bloqueadoAte != null && agora.isBefore(bloqueadoAte);
		}

		boolean esquecido(Instant agora, Duration esquecerApos) {
			return !bloqueadoEm(agora) && !agora.isBefore(ultimaTentativa.plus(esquecerApos));
		}
	}
}
