package br.com.healthstaff.backend.importacao;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.extrator.ArquivoParaExtracao;
import br.com.healthstaff.backend.extrator.ExtratorClient;
import br.com.healthstaff.backend.extrator.ExtratorIndisponivelException;
import br.com.healthstaff.backend.extrator.PessoaConhecida;
import br.com.healthstaff.backend.extrator.SolicitacaoExtracao;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;

/**
 * Fluxo de importação: upload -> extrator (Python) -> banco.
 *
 * <p>A chamada ao extrator acontece fora de transação (pode levar segundos e
 * não deve segurar conexão do banco); a gravação é uma transação única em
 * {@link ImportacaoService}.
 */
@Service
public class ImportarArquivoService {

	private static final Logger log = LoggerFactory.getLogger(ImportarArquivoService.class);

	private final ExtratorClient extrator;
	private final ImportacaoService importacoes;
	private final ImportacaoConsulta consulta;
	private final ImportacaoRepository repositorio;
	private final ColaboradorRepository colaboradores;

	public ImportarArquivoService(ExtratorClient extrator, ImportacaoService importacoes, ImportacaoConsulta consulta,
			ImportacaoRepository repositorio, ColaboradorRepository colaboradores) {
		this.extrator = extrator;
		this.importacoes = importacoes;
		this.consulta = consulta;
		this.repositorio = repositorio;
		this.colaboradores = colaboradores;
	}

	/**
	 * Envia a escala junto com o cadastro já conhecido (matrícula + nome) para
	 * o extrator associar os nomes da escala, mesmo sem o relatório do Two na
	 * mesma requisição.
	 */
	public ImportacaoResposta importarEscala(MultipartFile arquivo) {
		var cadastro = colaboradores.findByMatriculaIsNotNullOrderByMatricula().stream()
				.map(c -> new PessoaConhecida(c.getMatricula(), c.getNome()))
				.toList();
		var resultado = extrator.extrair(SolicitacaoExtracao.somenteEscala(paraExtracao(arquivo), cadastro));
		return registrar(exigir(resultado.getEscala(), "escala"));
	}

	/** @param dataReferencia data dos saldos; nula = data da importação (o extrator registra um aviso) */
	public ImportacaoResposta importarPonto(MultipartFile arquivo, LocalDate dataReferencia) {
		var resultado = extrator.extrair(SolicitacaoExtracao.somenteBancoHoras(paraExtracao(arquivo), dataReferencia));
		return registrar(exigir(resultado.getBancoHoras(), "banco de horas"));
	}

	ImportacaoResposta registrar(LoteImportacao lote) {
		try {
			var resultado = importacoes.registrar(lote);
			return consulta.detalhar(resultado.id(), resultado.duplicada());
		}
		catch (DataIntegrityViolationException e) {
			// Mesmo arquivo enviado em paralelo: a outra requisição gravou primeiro.
			var origem = lote.getOrigem();
			var existente = repositorio
					.findByTipoDocumentoAndHashSha256(origem.getTipoDocumento().getValue(), origem.getHashSha256())
					.orElseThrow(() -> e);
			log.info("importação concorrente do mesmo arquivo; usando {}", existente.getId());
			return consulta.detalhar(existente.getId(), true);
		}
	}

	private static ArquivoParaExtracao paraExtracao(MultipartFile arquivo) {
		if (arquivo == null || arquivo.isEmpty()) {
			throw new ArquivoInvalidoException("arquivo vazio");
		}
		var nome = arquivo.getOriginalFilename();
		if (nome == null || nome.isBlank()) {
			throw new ArquivoInvalidoException("arquivo sem nome; a extensão (.xlsx, .pdf, .csv) é necessária");
		}
		return new ArquivoParaExtracao(nome, arquivo.getResource());
	}

	private static LoteImportacao exigir(LoteImportacao lote, String esperado) {
		if (lote == null) {
			throw new ExtratorIndisponivelException("extrator não devolveu o lote de " + esperado);
		}
		return lote;
	}
}
