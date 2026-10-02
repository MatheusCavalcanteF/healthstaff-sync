package br.com.healthstaff.backend.regras;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Categoria profissional deduzida do cargo/função da escala ("Enfermeira
 * Diurno Ímpar", "Téc. de Enf. Noturno", "AUX. ADMINISTRATIVO FOLGUISTA",
 * "COORD. ENFERMAGEM"). Técnico é testado antes de enfermeiro porque "Téc.
 * de Enf." também contém "Enf".
 */
public enum CategoriaProfissional {

	ENFERMEIRO, TECNICO_ENFERMAGEM, ADMINISTRATIVO, OUTRO;

	private static final Set<String> TECNICO = Set.of("TEC", "TECNICO", "TECNICA", "TECNICOS", "TECNICAS");
	private static final Set<String> ADMINISTRATIVO_ = Set.of("AUX", "AUXILIAR", "ADMINISTRATIVO", "ADMINISTRATIVA",
			"ADM", "ASSISTENTE", "RECEPCIONISTA", "SECRETARIA", "SECRETARIO");
	private static final Set<String> ENFERMEIRO_ = Set.of("ENF", "ENFA", "ENFO", "ENFERMEIRO", "ENFERMEIRA",
			"ENFERMEIROS", "ENFERMEIRAS", "ENFERMAGEM");

	public static CategoriaProfissional de(String cargo) {
		if (cargo == null || cargo.isBlank()) {
			return OUTRO;
		}
		var semAcento = Normalizer.normalize(cargo, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
				.toUpperCase(Locale.ROOT);
		var palavras = Arrays.stream(semAcento.split("[^A-Z]+")).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
		if (palavras.stream().anyMatch(TECNICO::contains)) {
			return TECNICO_ENFERMAGEM;
		}
		if (palavras.stream().anyMatch(ADMINISTRATIVO_::contains)) {
			return ADMINISTRATIVO;
		}
		if (palavras.stream().anyMatch(ENFERMEIRO_::contains)) {
			return ENFERMEIRO;
		}
		return OUTRO;
	}
}
