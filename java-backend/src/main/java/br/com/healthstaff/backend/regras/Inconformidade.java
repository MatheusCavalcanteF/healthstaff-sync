package br.com.healthstaff.backend.regras;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import br.com.healthstaff.backend.regras.TipoInconformidade.Gravidade;

/** Alerta de regra de negócio gravado numa análise. */
@Entity
@Table(name = "inconformidade")
public class Inconformidade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "analise_id", nullable = false)
	private Long analiseId;

	@Column(name = "colaborador_id", nullable = false)
	private Long colaboradorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private TipoInconformidade tipo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Gravidade gravidade;

	@Column(nullable = false)
	private LocalDate data;

	@Column(name = "plantao_id")
	private Long plantaoId;

	@Column(name = "plantao_relacionado_id")
	private Long plantaoRelacionadoId;

	@Column(name = "valor_minutos")
	private Integer valorMinutos;

	@Column(name = "limite_minutos")
	private Integer limiteMinutos;

	@Column(nullable = false, columnDefinition = "text")
	private String descricao;

	protected Inconformidade() {
	}

	Inconformidade(long analiseId, Achado achado) {
		this.analiseId = analiseId;
		this.colaboradorId = achado.colaboradorId();
		this.tipo = achado.tipo();
		this.gravidade = achado.tipo().gravidade();
		this.data = achado.data();
		this.plantaoId = achado.plantaoId();
		this.plantaoRelacionadoId = achado.plantaoRelacionadoId();
		this.valorMinutos = achado.valorMinutos();
		this.limiteMinutos = achado.limiteMinutos();
		this.descricao = achado.descricao();
	}

	public Long getId() {
		return id;
	}

	public Long getAnaliseId() {
		return analiseId;
	}

	public Long getColaboradorId() {
		return colaboradorId;
	}

	public TipoInconformidade getTipo() {
		return tipo;
	}

	public Gravidade getGravidade() {
		return gravidade;
	}

	public LocalDate getData() {
		return data;
	}

	public Long getPlantaoId() {
		return plantaoId;
	}

	public Long getPlantaoRelacionadoId() {
		return plantaoRelacionadoId;
	}

	public Integer getValorMinutos() {
		return valorMinutos;
	}

	public Integer getLimiteMinutos() {
		return limiteMinutos;
	}

	public String getDescricao() {
		return descricao;
	}
}
