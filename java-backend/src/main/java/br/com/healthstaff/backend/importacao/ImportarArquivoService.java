package br.com.healthstaff.backend.importacao;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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
import br.com.healthstaff.backend.regras.Analise.Gatilho;
import br.com.healthstaff.backend.regras.MotorRegrasService;

/**
 * Fluxo de importação: upload -> extrator (Python) -> banco.
 *
 * <p>A chamada ao extrator acontece fora de transação (pode levar segundos e
 * não deve segurar conexão do banco); a gravação é uma transação única em
 * {@link ImportacaoService}. Depois de gravar um arquivo novo, o motor de regras
 * reanalisa a competência afetada; uma falha na análise não desfaz a importação
 * (fica no log e pode ser reprocessada em {@code POST /api/v1/analises/{competencia}}).
 */
@Service
public class ImportarArquivoService {

	private static final Logger log = LoggerFactory.getLogger(ImportarArquivoService.class);

	private final ExtratorClient extrator;
	private final ImportacaoService importacoes;
	private final ImportacaoConsulta consulta;
	private final ImportacaoRepository repositorio;
	private final ColaboradorRepository colaboradores;
	private final MotorRegrasService motor;

	public ImportarArquivoService(ExtratorClient extrator, ImportacaoService importacoes, ImportacaoConsulta consulta,
			ImportacaoRepository repositorio, ColaboradorRepository colaboradores, MotorRegrasService motor) {
		this.extrator = extrator;
		this.importacoes = importacoes;
		this.consulta = consulta;
		this.repositorio = repositorio;
		this.colaboradores = colaboradores;
		this.motor = motor;
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
		var resposta = registrar(exigir(resultado.getEscala(), "escala"));
		analisar(resposta, Gatilho.IMPORTACAO_ESCALA,
				resposta.escalas().stream().map(e -> YearMonth.from(e.competencia())).distinct().toList());
		return resposta;
	}

	/** @param dataReferencia data dos saldos; nula = data da importação (o extrator registra um aviso) */
	public ImportacaoResposta importarPonto(MultipartFile arquivo, LocalDate dataReferencia) {
		var resultado = extrator.extrair(SolicitacaoExtracao.somenteBancoHoras(paraExtracao(arquivo), dataReferencia));
		var resposta = registrar(exigir(resultado.getBancoHoras(), "banco de horas"));
		analisar(resposta, Gatilho.IMPORTACAO_PONTO, List.of(YearMonth.from(resposta.periodoFim())));
		return resposta;
	}

	/** Reanalisa as competências afetadas por um arquivo novo (duplicado não muda nada). */
	private void analisar(ImportacaoResposta resposta, Gatilho gatilho, List<YearMonth> competencias) {
		if (resposta.duplicada()) {
			return;
		}
		for (var competencia : competencias) {
			try {
				motor.analisar(competencia, gatilho, resposta.id());
			}
			catch (RuntimeException e) {
				log.error("importação {} gravada, mas a análise de {} falhou; reprocesse em POST /api/v1/analises/{}",
						resposta.id(), competencia, competencia, e);
			}
		}
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
