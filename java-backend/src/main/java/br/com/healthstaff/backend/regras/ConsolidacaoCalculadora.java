package br.com.healthstaff.backend.regras;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import br.com.healthstaff.backend.colaborador.CategoriaProfissional;

/**
 * Consolida, por colaborador e competência, a carga cumprida na escala com a
 * carga contratual e o saldo de banco de horas do Two, e aponta as
 * combinações que pioram o banco:
 *
 * <ul>
 * <li>saldo alto e carga cumprida acima da contratual -> {@link TipoInconformidade#SALDO_POSITIVO_ESCALA_ACIMA};</li>
 * <li>saldo baixo e carga cumprida abaixo da contratual -> {@link TipoInconformidade#SALDO_NEGATIVO_ESCALA_ABAIXO};</li>
 * <li>folga "BH" sem saldo positivo -> {@link TipoInconformidade#FOLGA_BH_SEM_SALDO}.</li>
 * </ul>
 *
 * <p><b>Carga cumprida = horas de plantão + crédito das folgas organizacionais
 * (FG).</b> A FG não reduz a carga contratual (180h continua 180h): cada FG
 * credita {@code creditoPorFolga} (12h), até a cota mensal da categoria
 * (enfermeiros 2, técnicos e administrativos 1). FG além da cota não é
 * creditada, para não esconder débito de horas.
 *
 * <p>Férias, licença e afastamento reduzem a carga esperada proporcionalmente
 * aos dias; com {@code diasAusenciaParaZerarCarga} (28) dias ou mais, a carga
 * esperada do mês é zero. Não há batidas de ponto (o Two exporta só o saldo): a comparação é
 * entre o planejado na escala e o contrato, projetando o saldo no fim do mês.
 */
public final class ConsolidacaoCalculadora {

	/** Saldo do Two mais recente até o fim da competência. */
	public record SaldoConhecido(int minutos, LocalDate dataReferencia) {
	}

	/**
	 * @param minutosEscalados  horas de plantão (trabalho efetivo escalado)
	 * @param minutosCreditoFg  crédito das FGs dentro da cota
	 * @param minutosCumpridos  plantão + crédito de FG (o que se compara com a carga)
	 * @param diferencaMinutos  cumpridos - esperados
	 */
	public record Resultado(
			long colaboradorId,
			String jornada,
			CategoriaProfissional categoria,
			int minutosEscalados,
			int folgasOrganizacionais,
			int folgasOrganizacionaisCreditadas,
			int minutosCreditoFg,
			int minutosCumpridos,
			Integer minutosEsperados,
			Integer diferencaMinutos,
			Integer saldoMinutos,
			LocalDate saldoDataReferencia,
			Integer saldoProjetadoMinutos,
			int plantoes,
			int diasAusencia,
			int folgasBancoHoras,
			List<Achado> achados) {
	}

	private final RegrasProperties regras;

	public ConsolidacaoCalculadora(RegrasProperties regras) {
		this.regras = regras;
	}

	public Resultado consolidar(long colaboradorId, String jornada, String cargo, List<MarcacaoDia> marcacoes,
			SaldoConhecido saldo, YearMonth competencia) {
		var doMes = marcacoes.stream().filter(m -> competencia.equals(YearMonth.from(m.data()))).toList();

		int escalados = (int) doMes.stream().mapToLong(m -> m.duracao().toMinutes()).sum();
		int plantoes = (int) doMes.stream().filter(MarcacaoDia::ehPlantao).count();
		int diasAusencia = (int) doMes.stream().filter(MarcacaoDia::ehAusenciaJustificada)
				.map(MarcacaoDia::data).distinct().count();
		var folgasBh = doMes.stream().filter(MarcacaoDia::ehFolgaBancoHoras).toList();

		var categoria = CategoriaProfissional.de(cargo);
		var fg = regras.folgaOrganizacional();
		int folgasOrganizacionais = (int) doMes.stream().filter(MarcacaoDia::ehFolgaOrganizacional)
				.map(MarcacaoDia::data).distinct().count();
		int creditadas = Math.min(folgasOrganizacionais, fg.cota(categoria));
		int creditoFg = creditadas * (int) fg.creditoPorFolga().toMinutes();
		int cumpridos = escalados + creditoFg;

		Integer esperados = regras.cargaMensal(jornada)
				.map(carga -> cargaEsperada(carga.toMinutes(), diasAusencia, competencia))
				.orElse(null);
		Integer diferenca = esperados == null ? null : cumpridos - esperados;
		Integer saldoMinutos = saldo == null ? null : saldo.minutos();
		Integer projetado = saldoMinutos == null || diferenca == null ? null : saldoMinutos + diferenca;

		var achados = new ArrayList<Achado>();
		var fimMes = competencia.atEndOfMonth();
		if (saldoMinutos != null && diferenca != null) {
			int alto = (int) regras.saldoAlto().toMinutes();
			int baixo = (int) regras.saldoBaixo().toMinutes();
			var composicao = composicao(escalados, creditoFg, creditadas, esperados);
			if (saldoMinutos >= alto && diferenca > 0) {
				achados.add(new Achado(TipoInconformidade.SALDO_POSITIVO_ESCALA_ACIMA, colaboradorId, fimMes, null, null,
						saldoMinutos, alto,
						"saldo de banco de horas de %s e escala %s acima da carga do mês (%s); saldo projetado %s"
								.formatted(Horas.comSinal(saldoMinutos), Horas.formatar(diferenca), composicao,
										Horas.comSinal(projetado))));
			}
			if (saldoMinutos <= baixo && diferenca < 0) {
				achados.add(new Achado(TipoInconformidade.SALDO_NEGATIVO_ESCALA_ABAIXO, colaboradorId, fimMes, null, null,
						saldoMinutos, baixo,
						"saldo de banco de horas de %s e escala %s abaixo da carga do mês (%s); saldo projetado %s"
								.formatted(Horas.comSinal(saldoMinutos), Horas.formatar(-diferenca), composicao,
										Horas.comSinal(projetado))));
			}
		}
		if (saldoMinutos != null && saldoMinutos <= 0) {
			for (var folga : folgasBh) {
				achados.add(new Achado(TipoInconformidade.FOLGA_BH_SEM_SALDO, colaboradorId, folga.data(), null, null,
						saldoMinutos, 0,
						"folga de compensação de banco de horas com saldo de %s (referência %s)".formatted(
								Horas.comSinal(saldoMinutos), Horas.dia(saldo.dataReferencia()))));
			}
		}

		return new Resultado(colaboradorId, jornada, categoria, escalados, folgasOrganizacionais, creditadas, creditoFg,
				cumpridos, esperados, diferenca, saldoMinutos, saldo == null ? null : saldo.dataReferencia(), projetado,
				plantoes, diasAusencia, folgasBh.size(), List.copyOf(achados));
	}

	/**
	 * Carga contratual proporcional aos dias sem ausência justificada. Ausente
	 * quase o mês todo, a sobra (1-3 dias, em geral folgas regulares da escala)
	 * não vira expectativa de trabalho: a carga do mês é zero.
	 */
	private int cargaEsperada(long cargaMensalMinutos, int diasAusencia, YearMonth competencia) {
		int diasMes = competencia.lengthOfMonth();
		if (diasAusencia >= Math.min(regras.diasAusenciaParaZerarCarga(), diasMes)) {
			return 0;
		}
		return (int) Math.round(cargaMensalMinutos * (double) (diasMes - diasAusencia) / diasMes);
	}

	/** "192h cumpridas = 180h de plantão + 12h de 1 FG; 180h previstas". */
	private static String composicao(int escalados, int creditoFg, int creditadas, int esperados) {
		if (creditoFg == 0) {
			return "%s de plantão; %s previstas".formatted(Horas.formatar(escalados), Horas.formatar(esperados));
		}
		return "%s cumpridas = %s de plantão + %s de %d FG; %s previstas".formatted(
				Horas.formatar(escalados + creditoFg), Horas.formatar(escalados), Horas.formatar(creditoFg), creditadas,
				Horas.formatar(esperados));
	}
}
