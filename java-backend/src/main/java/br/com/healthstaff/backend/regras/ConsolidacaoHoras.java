package br.com.healthstaff.backend.regras;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Horas escaladas x carga contratual x saldo do Two, por colaborador, numa análise. */
@Entity
@Table(name = "consolidacao_horas")
public class ConsolidacaoHoras {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "analise_id", nullable = false)
	private Long analiseId;

	@Column(name = "colaborador_id", nullable = false)
	private Long colaboradorId;

	@Column(length = 30)
	private String jornada;

	@Column(length = 30)
	private String categoria;

	@Column(name = "minutos_escalados", nullable = false)
	private int minutosEscalados;

	@Column(name = "folgas_organizacionais", nullable = false)
	private int folgasOrganizacionais;

	@Column(name = "folgas_organizacionais_creditadas", nullable = false)
	private int folgasOrganizacionaisCreditadas;

	@Column(name = "minutos_credito_fg", nullable = false)
	private int minutosCreditoFg;

	@Column(name = "minutos_cumpridos", nullable = false)
	private int minutosCumpridos;

	@Column(name = "minutos_esperados")
	private Integer minutosEsperados;

	@Column(name = "diferenca_minutos")
	private Integer diferencaMinutos;

	@Column(name = "saldo_minutos")
	private Integer saldoMinutos;

	@Column(name = "saldo_data_referencia")
	private LocalDate saldoDataReferencia;

	@Column(name = "saldo_projetado_minutos")
	private Integer saldoProjetadoMinutos;

	@Column(nullable = false)
	private int plantoes;

	@Column(name = "dias_ausencia", nullable = false)
	private int diasAusencia;

	@Column(name = "folgas_banco_horas", nullable = false)
	private int folgasBancoHoras;

	protected ConsolidacaoHoras() {
	}

	ConsolidacaoHoras(long analiseId, ConsolidacaoCalculadora.Resultado resultado) {
		this.analiseId = analiseId;
		this.colaboradorId = resultado.colaboradorId();
		this.jornada = resultado.jornada();
		this.categoria = resultado.categoria().name();
		this.minutosEscalados = resultado.minutosEscalados();
		this.folgasOrganizacionais = resultado.folgasOrganizacionais();
		this.folgasOrganizacionaisCreditadas = resultado.folgasOrganizacionaisCreditadas();
		this.minutosCreditoFg = resultado.minutosCreditoFg();
		this.minutosCumpridos = resultado.minutosCumpridos();
		this.minutosEsperados = resultado.minutosEsperados();
		this.diferencaMinutos = resultado.diferencaMinutos();
		this.saldoMinutos = resultado.saldoMinutos();
		this.saldoDataReferencia = resultado.saldoDataReferencia();
		this.saldoProjetadoMinutos = resultado.saldoProjetadoMinutos();
		this.plantoes = resultado.plantoes();
		this.diasAusencia = resultado.diasAusencia();
		this.folgasBancoHoras = resultado.folgasBancoHoras();
	}

	public Long getColaboradorId() {
		return colaboradorId;
	}

	public String getJornada() {
		return jornada;
	}

	public String getCategoria() {
		return categoria;
	}

	public int getMinutosEscalados() {
		return minutosEscalados;
	}

	public int getFolgasOrganizacionais() {
		return folgasOrganizacionais;
	}

	public int getFolgasOrganizacionaisCreditadas() {
		return folgasOrganizacionaisCreditadas;
	}

	public int getMinutosCreditoFg() {
		return minutosCreditoFg;
	}

	public int getMinutosCumpridos() {
		return minutosCumpridos;
	}

	public Integer getMinutosEsperados() {
		return minutosEsperados;
	}

	public Integer getDiferencaMinutos() {
		return diferencaMinutos;
	}

	public Integer getSaldoMinutos() {
		return saldoMinutos;
	}

	public LocalDate getSaldoDataReferencia() {
		return saldoDataReferencia;
	}

	public Integer getSaldoProjetadoMinutos() {
		return saldoProjetadoMinutos;
	}

	public int getPlantoes() {
		return plantoes;
	}

	public int getDiasAusencia() {
		return diasAusencia;
	}

	public int getFolgasBancoHoras() {
		return folgasBancoHoras;
	}
}
