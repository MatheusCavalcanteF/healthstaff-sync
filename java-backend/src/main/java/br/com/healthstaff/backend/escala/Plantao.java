package br.com.healthstaff.backend.escala;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

/**
 * Uma marcação da escala (plantão, folga, férias...). O setor pertence ao
 * plantão, não ao colaborador: o folguista aparece em setores diferentes.
 * Horários são locais (sem fuso), como na escala.
 */
@Entity
@Table(name = "plantao")
public class Plantao {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "escala_id", nullable = false)
	private Escala escala;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "setor_id", nullable = false)
	private Setor setor;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "colaborador_id", nullable = false)
	private Colaborador colaborador;

	@Column(nullable = false)
	private LocalDate data;

	@Column(nullable = false, length = 20)
	private String tipo;

	@Column(length = 20)
	private String turno;

	@Column(name = "codigo_original", nullable = false, columnDefinition = "text")
	private String codigoOriginal;

	private LocalDateTime inicio;

	private LocalDateTime fim;

	@Column(nullable = false)
	private boolean folguista;

	private String observacao;

	protected Plantao() {
	}

	Plantao(Escala escala, Colaborador colaborador, boolean folguista,
			br.com.healthstaff.backend.extrator.contrato.Plantao origem) {
		this.escala = escala;
		this.setor = escala.getSetor();
		this.colaborador = colaborador;
		this.folguista = folguista;
		this.data = origem.getData();
		this.tipo = origem.getTipo().getValue();
		this.turno = origem.getTurno() == null ? null : origem.getTurno().getValue();
		this.codigoOriginal = origem.getCodigoOriginal();
		this.inicio = origem.getInicio();
		this.fim = origem.getFim();
		this.observacao = origem.getObservacao();
	}

	public Long getId() {
		return id;
	}

	public Escala getEscala() {
		return escala;
	}

	public Setor getSetor() {
		return setor;
	}

	public Colaborador getColaborador() {
		return colaborador;
	}

	public LocalDate getData() {
		return data;
	}

	public String getTipo() {
		return tipo;
	}

	public String getTurno() {
		return turno;
	}

	public String getCodigoOriginal() {
		return codigoOriginal;
	}

	public LocalDateTime getInicio() {
		return inicio;
	}

	public LocalDateTime getFim() {
		return fim;
	}

	public boolean isFolguista() {
		return folguista;
	}

	public String getObservacao() {
		return observacao;
	}
}
