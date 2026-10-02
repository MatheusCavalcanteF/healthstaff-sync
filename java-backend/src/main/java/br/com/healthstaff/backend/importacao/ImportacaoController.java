package br.com.healthstaff.backend.importacao;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Importações enviadas pelo frontend.
 *
 * <ul>
 * <li>201 Created: arquivo novo, gravado ({@code Location} aponta para o GET).</li>
 * <li>200 OK com {@code duplicada=true}: o mesmo arquivo já tinha sido importado.</li>
 * <li>Erros em Problem Details (RFC 9457) com a propriedade {@code codigo}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/importacoes")
class ImportacaoController {

	private final ImportarArquivoService importar;
	private final ImportacaoConsulta consulta;

	ImportacaoController(ImportarArquivoService importar, ImportacaoConsulta consulta) {
		this.importar = importar;
		this.consulta = consulta;
	}

	/** Escala em grade mensal (PDF ou Excel). */
	@PostMapping(path = "/escalas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ImportacaoResposta> importarEscala(@RequestPart("arquivo") MultipartFile arquivo) {
		return criadaOuExistente(importar.importarEscala(arquivo));
	}

	/**
	 * Relatório de Banco de Horas do Two (Excel ou CSV).
	 *
	 * @param dataReferencia data dos saldos (AAAA-MM-DD); o frontend pode usar a
	 *                       data de modificação do arquivo ({@code File.lastModified})
	 */
	@PostMapping(path = "/ponto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ImportacaoResposta> importarPonto(
			@RequestPart("arquivo") MultipartFile arquivo,
			@RequestParam(name = "dataReferencia", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataReferencia) {
		return criadaOuExistente(importar.importarPonto(arquivo, dataReferencia));
	}

	@GetMapping("/{id}")
	ImportacaoResposta detalhar(@PathVariable UUID id) {
		return consulta.detalhar(id, false);
	}

	private static ResponseEntity<ImportacaoResposta> criadaOuExistente(ImportacaoResposta resposta) {
		if (resposta.duplicada()) {
			return ResponseEntity.ok(resposta);
		}
		var local = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/v1/importacoes/{id}")
				.buildAndExpand(resposta.id())
				.toUri();
		return ResponseEntity.created(local).body(resposta);
	}
}
