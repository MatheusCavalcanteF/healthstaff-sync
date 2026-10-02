package br.com.healthstaff.backend.colaborador;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Pessoa que aparece na escala e/ou no sistema de ponto.
 *
 * <p>Sem matrícula, é um <b>cadastro provisório</b> (recém-contratado, RPA,
 * coordenação fora do Two), identificado pelo nome normalizado. Quando a
 * matrícula aparece numa importação posterior, o mesmo registro é
 * promovido — o histórico de plantões continua ligado a ele.
 */
@Entity
@Table(name = "colaborador")
public class Colaborador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = 30)
	private String matricula;

	@Column(nullable = false)
	private String nome;

	@Column(name = "nome_normalizado", nullable = false)
	private String nomeNormalizado;

	@Column(name = "registro_conselho", length = 30)
	private String registroConselho;

	private String cargo;

	@Column(length = 30)
	private String jornada;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "atualizado_em", nullable = false)
	private Instant atualizadoEm;

	protected Colaborador() {
	}

	Colaborador(String matricula, String nome, String nomeNormalizado) {
		this.matricula = matricula;
		this.nome = nome;
		this.nomeNormalizado = nomeNormalizado;
		this.criadoEm = Instant.now();
		this.atualizadoEm = this.criadoEm;
	}

	public boolean isProvisorio() {
		return matricula == null;
	}

	/** Atribui a matrícula a um cadastro provisório (vínculo com o Two). */
	void promover(String matricula) {
		if (!isProvisorio()) {
			throw new IllegalStateException("colaborador " + id + " já tem matrícula " + this.matricula);
		}
		this.matricula = matricula;
		tocar();
	}

	/**
	 * Atualiza dados descritivos. Valores nulos não apagam o que já existe:
	 * o relatório do Two não traz cargo/jornada/COREN, a escala não traz a
	 * matrícula — cada fonte complementa a outra.
	 *
	 * @param nomeOficial se verdadeiro (fonte: Two), o nome substitui o atual
	 */
	void complementar(DadosColaborador dados, boolean nomeOficial) {
		if (nomeOficial && dados.nome() != null) {
			nome = dados.nome();
			nomeNormalizado = dados.nomeNormalizado();
		}
		if (dados.registroConselho() != null) {
			registroConselho = dados.registroConselho();
		}
		if (dados.cargo() != null) {
			cargo = dados.cargo();
		}
		if (dados.jornada() != null) {
			jornada = dados.jornada();
		}
		tocar();
	}

	private void tocar() {
		atualizadoEm = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getMatricula() {
		return matricula;
	}

	public String getNome() {
		return nome;
	}

	public String getNomeNormalizado() {
		return nomeNormalizado;
	}

	public String getRegistroConselho() {
		return registroConselho;
	}

	public String getCargo() {
		return cargo;
	}

	public String getJornada() {
		return jornada;
	}
}
