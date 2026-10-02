package br.com.healthstaff.backend.escala;

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

/**
 * Uma versão da escala de um setor numa competência (mês). Cada importação
 * cria uma versão nova; só a mais recente é {@code vigente}. As anteriores
 * ficam como histórico das mudanças da escala.
 */
@Entity
@Table(name = "escala")
public class Escala {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "setor_id", nullable = false)
	private Setor setor;

	@Column(nullable = false)
	private LocalDate competencia;

	@Column(nullable = false)
	private int versao;

	@Column(nullable = false)
	private boolean vigente;

	@Column(name = "importacao_id", nullable = false)
	private UUID importacaoId;

	protected Escala() {
	}

	Escala(Setor setor, LocalDate competencia, int versao, UUID importacaoId) {
		if (competencia.getDayOfMonth() != 1) {
			throw new IllegalArgumentException("competência deve ser o primeiro dia do mês: " + competencia);
		}
		this.setor = setor;
		this.competencia = competencia;
		this.versao = versao;
		this.vigente = true;
		this.importacaoId = importacaoId;
	}

	void substituir() {
		vigente = false;
	}

	public Long getId() {
		return id;
	}

	public Setor getSetor() {
		return setor;
	}

	public LocalDate getCompetencia() {
		return competencia;
	}

	public int getVersao() {
		return versao;
	}

	public boolean isVigente() {
		return vigente;
	}

	public UUID getImportacaoId() {
		return importacaoId;
	}
}
