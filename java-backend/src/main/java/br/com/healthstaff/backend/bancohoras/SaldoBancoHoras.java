package br.com.healthstaff.backend.bancohoras;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import br.com.healthstaff.backend.colaborador.Colaborador;

/** Saldo de banco de horas de um colaborador num relatório do Two (um retrato por importação). */
@Entity
@Table(name = "saldo_banco_horas")
public class SaldoBancoHoras {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "importacao_id", nullable = false)
	private UUID importacaoId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "colaborador_id", nullable = false)
	private Colaborador colaborador;

	@Column(nullable = false)
	private int minutos;

	@Column(name = "data_referencia", nullable = false)
	private LocalDate dataReferencia;

	@Column(name = "inicio_apuracao")
	private LocalDate inicioApuracao;

	private String departamento;

	protected SaldoBancoHoras() {
	}

	SaldoBancoHoras(UUID importacaoId, Colaborador colaborador, int minutos, LocalDate dataReferencia,
			LocalDate inicioApuracao, String departamento) {
		this.importacaoId = importacaoId;
		this.colaborador = colaborador;
		this.minutos = minutos;
		this.dataReferencia = dataReferencia;
		this.inicioApuracao = inicioApuracao;
		this.departamento = departamento;
	}

	public UUID getImportacaoId() {
		return importacaoId;
	}

	public Colaborador getColaborador() {
		return colaborador;
	}

	public int getMinutos() {
		return minutos;
	}

	public LocalDate getDataReferencia() {
		return dataReferencia;
	}

	public LocalDate getInicioApuracao() {
		return inicioApuracao;
	}

	public String getDepartamento() {
		return departamento;
	}
}
