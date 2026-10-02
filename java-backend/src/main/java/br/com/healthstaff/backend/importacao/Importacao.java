package br.com.healthstaff.backend.importacao;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;

/** Um lote devolvido pelo extrator e registrado no banco. */
@Entity
@Table(name = "importacao")
public class Importacao {

	@Id
	private UUID id;

	@Column(name = "tipo_documento", nullable = false, length = 30)
	private String tipoDocumento;

	@Column(name = "nome_arquivo", nullable = false)
	private String nomeArquivo;

	@Column(name = "hash_sha256", nullable = false, length = 64)
	private String hashSha256;

	@Column(name = "tamanho_bytes", nullable = false)
	private long tamanhoBytes;

	@Column(name = "total_paginas")
	private Integer totalPaginas;

	@Column(nullable = false, length = 60)
	private String extrator;

	@Column(name = "versao_extrator", nullable = false, length = 20)
	private String versaoExtrator;

	@Column(name = "versao_contrato", nullable = false, length = 10)
	private String versaoContrato;

	@Column(name = "periodo_inicio", nullable = false)
	private LocalDate periodoInicio;

	@Column(name = "periodo_fim", nullable = false)
	private LocalDate periodoFim;

	@Column(name = "gerado_em", nullable = false)
	private LocalDateTime geradoEm;

	@Column(name = "importado_em", nullable = false)
	private Instant importadoEm;

	@Column(name = "total_colaboradores", nullable = false)
	private int totalColaboradores;

	@Column(name = "total_plantoes", nullable = false)
	private int totalPlantoes;

	@Column(name = "possui_erros", nullable = false)
	private boolean possuiErros;

	protected Importacao() {
	}

	static Importacao de(LoteImportacao lote) {
		var origem = lote.getOrigem();
		var importacao = new Importacao();
		importacao.id = lote.getIdLote();
		importacao.tipoDocumento = origem.getTipoDocumento().getValue();
		importacao.nomeArquivo = origem.getNomeArquivo();
		importacao.hashSha256 = origem.getHashSha256();
		importacao.tamanhoBytes = origem.getTamanhoBytes();
		importacao.totalPaginas = origem.getTotalPaginas();
		importacao.extrator = origem.getExtrator();
		importacao.versaoExtrator = origem.getVersaoExtrator();
		importacao.versaoContrato = lote.getVersaoContrato().getValue();
		importacao.periodoInicio = lote.getPeriodoInicio();
		importacao.periodoFim = lote.getPeriodoFim();
		importacao.geradoEm = lote.getGeradoEm();
		importacao.importadoEm = Instant.now();
		importacao.totalColaboradores = lote.getTotalColaboradores();
		importacao.totalPlantoes = lote.getTotalPlantoes();
		importacao.possuiErros = Boolean.TRUE.equals(lote.getPossuiErros());
		return importacao;
	}

	public UUID getId() {
		return id;
	}

	public String getTipoDocumento() {
		return tipoDocumento;
	}

	public String getNomeArquivo() {
		return nomeArquivo;
	}

	public String getHashSha256() {
		return hashSha256;
	}

	public LocalDate getPeriodoInicio() {
		return periodoInicio;
	}

	public LocalDate getPeriodoFim() {
		return periodoFim;
	}

	public Instant getImportadoEm() {
		return importadoEm;
	}

	public boolean isPossuiErros() {
		return possuiErros;
	}

	public int getTotalColaboradores() {
		return totalColaboradores;
	}

	public int getTotalPlantoes() {
		return totalPlantoes;
	}
}
