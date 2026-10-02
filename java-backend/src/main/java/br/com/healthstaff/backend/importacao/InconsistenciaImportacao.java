package br.com.healthstaff.backend.importacao;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import br.com.healthstaff.backend.extrator.contrato.Inconsistencia;

/** Problema apontado pelo extrator, guardado para revisão do RH. */
@Entity
@Table(name = "inconsistencia")
public class InconsistenciaImportacao {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "importacao_id", nullable = false)
	private UUID importacaoId;

	@Column(nullable = false, length = 60)
	private String codigo;

	@Column(nullable = false, length = 10)
	private String severidade;

	@Column(nullable = false, columnDefinition = "text")
	private String mensagem;

	private Integer pagina;

	@Column(length = 100)
	private String aba;

	private Integer linha;

	private String setor;

	@Column(length = 30)
	private String matricula;

	@Column(name = "nome_colaborador")
	private String nomeColaborador;

	private LocalDate data;

	@Column(name = "valor_bruto", columnDefinition = "text")
	private String valorBruto;

	protected InconsistenciaImportacao() {
	}

	static InconsistenciaImportacao de(UUID importacaoId, Inconsistencia origem) {
		var inconsistencia = new InconsistenciaImportacao();
		inconsistencia.importacaoId = importacaoId;
		inconsistencia.codigo = origem.getCodigo().getValue();
		inconsistencia.severidade = origem.getSeveridade().getValue();
		inconsistencia.mensagem = origem.getMensagem();
		inconsistencia.pagina = origem.getPagina();
		inconsistencia.aba = origem.getAba();
		inconsistencia.linha = origem.getLinha();
		inconsistencia.setor = origem.getSetor();
		inconsistencia.matricula = origem.getMatricula();
		inconsistencia.nomeColaborador = origem.getNomeColaborador();
		inconsistencia.data = origem.getData();
		inconsistencia.valorBruto = origem.getValorBruto();
		return inconsistencia;
	}

	public UUID getImportacaoId() {
		return importacaoId;
	}

	public String getCodigo() {
		return codigo;
	}

	public String getSeveridade() {
		return severidade;
	}

	public String getMensagem() {
		return mensagem;
	}

	public String getAba() {
		return aba;
	}

	public Integer getLinha() {
		return linha;
	}

	public String getNomeColaborador() {
		return nomeColaborador;
	}
}
