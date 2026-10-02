package br.com.healthstaff.backend.importacao;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.healthstaff.backend.bancohoras.BancoHorasService;
import br.com.healthstaff.backend.escala.EscalaService;
import br.com.healthstaff.backend.escala.EscalaService.VersaoRegistrada;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;

/**
 * Grava no banco um lote devolvido pelo extrator, numa única transação:
 * importação, inconsistências e, conforme o tipo, escala (versionada) ou
 * saldos do banco de horas.
 */
@Service
public class ImportacaoService {

	private static final Logger log = LoggerFactory.getLogger(ImportacaoService.class);

	/**
	 * @param duplicada verdadeiro quando o mesmo arquivo já tinha sido importado;
	 *                  nesse caso nada é gravado e {@code id} é o da importação original
	 */
	public record ResultadoImportacao(
			UUID id,
			String tipoDocumento,
			boolean duplicada,
			int inconsistencias,
			List<VersaoRegistrada> escalas,
			int saldos) {
	}

	private final ImportacaoRepository importacoes;
	private final InconsistenciaImportacaoRepository inconsistencias;
	private final EscalaService escalas;
	private final BancoHorasService bancoHoras;

	public ImportacaoService(ImportacaoRepository importacoes, InconsistenciaImportacaoRepository inconsistencias,
			EscalaService escalas, BancoHorasService bancoHoras) {
		this.importacoes = importacoes;
		this.inconsistencias = inconsistencias;
		this.escalas = escalas;
		this.bancoHoras = bancoHoras;
	}

	@Transactional
	public ResultadoImportacao registrar(LoteImportacao lote) {
		var tipo = lote.getOrigem().getTipoDocumento();
		var existente = importacoes.findByTipoDocumentoAndHashSha256(tipo.getValue(), lote.getOrigem().getHashSha256());
		if (existente.isPresent()) {
			log.info("arquivo já importado ({}): importação {}", tipo.getValue(), existente.get().getId());
			return new ResultadoImportacao(existente.get().getId(), tipo.getValue(), true, 0, List.of(), 0);
		}

		var importacao = importacoes.save(Importacao.de(lote));
		var id = importacao.getId();
		inconsistencias.saveAll(lote.getInconsistencias().stream()
				.map(origem -> InconsistenciaImportacao.de(id, origem))
				.toList());

		var resultado = switch (tipo) {
			case ESCALA_PDF, ESCALA_EXCEL -> new ResultadoImportacao(id, tipo.getValue(), false,
					lote.getInconsistencias().size(), escalas.registrar(id, lote), 0);
			case PONTO_EXCEL, PONTO_CSV -> new ResultadoImportacao(id, tipo.getValue(), false,
					lote.getInconsistencias().size(), List.of(), bancoHoras.registrar(id, lote));
			default -> throw new IllegalArgumentException("tipo de documento não suportado: " + tipo);
		};
		log.info("importação {} registrada: tipo={} escalas={} saldos={} inconsistencias={}",
				id, tipo.getValue(), resultado.escalas().size(), resultado.saldos(), resultado.inconsistencias());
		return resultado;
	}
}
