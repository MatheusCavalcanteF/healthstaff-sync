package br.com.healthstaff.backend.regras;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import br.com.healthstaff.backend.colaborador.CategoriaProfissional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros do motor de regras ({@code healthstaff.regras.*}).
 *
 * @param descansoMinimoInterjornada   descanso mínimo entre jornadas (CLT art. 66)
 * @param intervaloMaximoMesmaJornada  plantões separados por até este intervalo formam uma
 *                                     única jornada (pausa intrajornada, ex.: "M/T")
 * @param jornadaMaxima                acima disso, a jornada (plantões emendados) gera alerta
 * @param saldoAlto                    saldo de banco de horas considerado alto
 * @param saldoBaixo                   saldo considerado baixo (negativo)
 * @param cargasMensais                carga mensal por jornada (chave normalizada, ex.: "12X36");
 *                                     jornadas "NNH" (semanais) usam NN x 5 se não estiverem aqui
 * @param folgaOrganizacional          crédito das folgas organizacionais (FG) na carga cumprida
 * @param diasAusenciaParaZerarCarga   com férias/licença/afastamento em pelo menos tantos dias do
 *                                     mês, a carga esperada é zero (a fração residual de 1-3 dias,
 *                                     em geral folgas regulares, geraria débito artificial)
 */
@ConfigurationProperties("healthstaff.regras")
public record RegrasProperties(
		@DefaultValue("11h") Duration descansoMinimoInterjornada,
		@DefaultValue("2h") Duration intervaloMaximoMesmaJornada,
		@DefaultValue("12h") Duration jornadaMaxima,
		@DefaultValue("20h") Duration saldoAlto,
		@DefaultValue("-10h") Duration saldoBaixo,
		Map<String, Duration> cargasMensais,
		@DefaultValue FolgaOrganizacional folgaOrganizacional,
		@DefaultValue("28") Integer diasAusenciaParaZerarCarga) {

	/**
	 * Folgas organizacionais (FG): não reduzem a carga contratual; contam como
	 * jornada cumprida, até a cota da categoria no mês. FG além da cota não é
	 * creditada (esconderia débito de horas).
	 *
	 * @param creditoPorFolga     horas creditadas por FG
	 * @param enfermeiro          FGs creditáveis por mês para enfermeiros(as)
	 * @param tecnicoEnfermagem   ... para técnicos(as) de enfermagem
	 * @param administrativo      ... para administrativos
	 * @param outros              ... para cargo não reconhecido
	 */
	public record FolgaOrganizacional(
			@DefaultValue("12h") Duration creditoPorFolga,
			@DefaultValue("2") int enfermeiro,
			@DefaultValue("1") int tecnicoEnfermagem,
			@DefaultValue("1") int administrativo,
			@DefaultValue("1") int outros) {

		public static FolgaOrganizacional padrao() {
			return new FolgaOrganizacional(Duration.ofHours(12), 2, 1, 1, 1);
		}

		public int cota(CategoriaProfissional categoria) {
			return switch (categoria) {
				case ENFERMEIRO -> enfermeiro;
				case TECNICO_ENFERMAGEM -> tecnicoEnfermagem;
				case ADMINISTRATIVO -> administrativo;
				case OUTRO -> outros;
			};
		}
	}

	/** 12x36: 15 plantões de 12h em média por mês. */
	static final Map<String, Duration> CARGAS_PADRAO = Map.of("12X36", Duration.ofHours(180));

	private static final Pattern SEMANAL = Pattern.compile("^(\\d{1,2})H(?:SEMANAIS|SEM|S)?$");

	public RegrasProperties {
		var cargas = new HashMap<>(CARGAS_PADRAO);
		if (cargasMensais != null) {
			cargasMensais.forEach((jornada, carga) -> cargas.put(chave(jornada), carga));
		}
		cargasMensais = Map.copyOf(cargas);
		if (folgaOrganizacional == null) {
			folgaOrganizacional = FolgaOrganizacional.padrao();
		}
		if (diasAusenciaParaZerarCarga == null) {
			diasAusenciaParaZerarCarga = 28;
		}
	}

	/** Padrões de produção (usados também nos testes de unidade). */
	public static RegrasProperties padrao() {
		return new RegrasProperties(Duration.ofHours(11), Duration.ofHours(2), Duration.ofHours(12),
				Duration.ofHours(20), Duration.ofHours(-10), null, null, null);
	}

	/**
	 * Carga mensal esperada para a jornada da escala ("12x36", "12X36", "40H").
	 * Semanais seguem o divisor da CLT (40h semanais = 200h/mês).
	 */
	public Optional<Duration> cargaMensal(String jornada) {
		if (jornada == null || jornada.isBlank()) {
			return Optional.empty();
		}
		var chave = chave(jornada);
		var configurada = cargasMensais.get(chave);
		if (configurada != null) {
			return Optional.of(configurada);
		}
		var semanal = SEMANAL.matcher(chave);
		return semanal.matches()
				? Optional.of(Duration.ofHours(Long.parseLong(semanal.group(1)) * 5))
				: Optional.empty();
	}

	static String chave(String jornada) {
		return jornada.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
	}
}
