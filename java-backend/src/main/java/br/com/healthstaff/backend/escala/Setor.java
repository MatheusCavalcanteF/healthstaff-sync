package br.com.healthstaff.backend.escala;

import java.text.Normalizer;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "setor")
public class Setor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String nome;

	/** Nome normalizado (sem acento, maiúsculo, espaços simples): identifica o setor. */
	@Column(nullable = false, unique = true)
	private String chave;

	protected Setor() {
	}

	Setor(String nome) {
		this.nome = nome.strip();
		this.chave = chave(nome);
	}

	static String chave(String nome) {
		var semAcento = Normalizer.normalize(nome, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		return semAcento.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}

	public Long getId() {
		return id;
	}

	public String getNome() {
		return nome;
	}

	public String getChave() {
		return chave;
	}
}
