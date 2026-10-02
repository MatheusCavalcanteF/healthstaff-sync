package br.com.healthstaff.backend.regras;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Uma execução do motor de regras sobre uma competência. Só a mais recente é vigente. */
@Entity
@Table(name = "analise")
public class Analise {

	public enum Gatilho {
		IMPORTACAO_ESCALA, IMPORTACAO_PONTO, MANUAL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private LocalDate competencia;

	@Column(name = "executada_em", nullable = false)
	private Instant executadaEm;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Gatilho gatilho;

	@Column(name = "importacao_id")
	private UUID importacaoId;

	@Column(nullable = false)
	private boolean vigente;

	@Column(name = "colaboradores_analisados", nullable = false)
	private int colaboradoresAnalisados;

	@Column(name = "total_inconformidades", nullable = false)
	private int totalInconformidades;

	protected Analise() {
	}

	Analise(YearMonth competencia, Gatilho gatilho, UUID importacaoId, int colaboradoresAnalisados,
			int totalInconformidades) {
		this.competencia = competencia.atDay(1);
		this.executadaEm = Instant.now();
		this.gatilho = gatilho;
		this.importacaoId = importacaoId;
		this.vigente = true;
		this.colaboradoresAnalisados = colaboradoresAnalisados;
		this.totalInconformidades = totalInconformidades;
	}

	void substituir() {
		vigente = false;
	}

	public Long getId() {
		return id;
	}

	public YearMonth getCompetencia() {
		return YearMonth.from(competencia);
	}

	public Instant getExecutadaEm() {
		return executadaEm;
	}

	public Gatilho getGatilho() {
		return gatilho;
	}

	public UUID getImportacaoId() {
		return importacaoId;
	}

	public boolean isVigente() {
		return vigente;
	}

	public int getColaboradoresAnalisados() {
		return colaboradoresAnalisados;
	}

	public int getTotalInconformidades() {
		return totalInconformidades;
	}
}
