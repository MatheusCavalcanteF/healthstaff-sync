package br.com.healthstaff.backend.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import br.com.healthstaff.backend.extrator.ExtracaoRejeitadaException;
import br.com.healthstaff.backend.extrator.ExtratorIndisponivelException;
import br.com.healthstaff.backend.importacao.ArquivoInvalidoException;
import br.com.healthstaff.backend.importacao.ImportacaoNaoEncontradaException;
import br.com.healthstaff.backend.regras.AnaliseNaoEncontradaException;
import br.com.healthstaff.backend.regras.SemEscalaVigenteException;
import br.com.healthstaff.backend.seguranca.ConflitoUsuarioException;
import br.com.healthstaff.backend.seguranca.CredenciaisInvalidasException;
import br.com.healthstaff.backend.seguranca.UsuarioNaoEncontradoException;

/**
 * Erros da API em Problem Details (RFC 9457). Todo corpo de erro traz a
 * propriedade {@code codigo}, estável, para o frontend decidir o que mostrar.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosApiHandler {

	private static final Logger log = LoggerFactory.getLogger(ErrosApiHandler.class);

	@ExceptionHandler(ExtracaoRejeitadaException.class)
	ProblemDetail arquivoRecusado(ExtracaoRejeitadaException e) {
		var erro = e.getErro();
		var problema = problema(HttpStatus.valueOf(e.getStatus()), "Arquivo recusado", erro.getMensagem(),
				erro.getCodigo());
		if (erro.getArquivo() != null) {
			problema.setProperty("arquivo", erro.getArquivo());
		}
		return problema;
	}

	@ExceptionHandler(ExtratorIndisponivelException.class)
	ProblemDetail extratorIndisponivel(ExtratorIndisponivelException e) {
		// Detalhe técnico só no log: não expõe endereço/credencial do serviço interno.
		log.error("falha ao chamar o extrator: {}", e.getMessage(), e);
		return problema(HttpStatus.SERVICE_UNAVAILABLE, "Extrator indisponível",
				"Não foi possível processar o arquivo agora. Tente novamente em instantes.", "EXTRATOR_INDISPONIVEL");
	}

	@ExceptionHandler(ArquivoInvalidoException.class)
	ProblemDetail arquivoInvalido(ArquivoInvalidoException e) {
		return problema(HttpStatus.BAD_REQUEST, "Arquivo inválido", e.getMessage(), "ARQUIVO_INVALIDO");
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ProblemDetail arquivoAusente(MissingServletRequestPartException e) {
		return problema(HttpStatus.BAD_REQUEST, "Arquivo ausente",
				"envie o arquivo no campo '" + e.getRequestPartName() + "'", "ARQUIVO_AUSENTE");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ProblemDetail arquivoGrande(MaxUploadSizeExceededException e) {
		return problema(HttpStatus.CONTENT_TOO_LARGE, "Arquivo muito grande",
				"o arquivo excede o tamanho máximo permitido", "ARQUIVO_MUITO_GRANDE");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
		return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido",
				"valor inválido para '" + e.getName() + "'" + dicaDeFormato(e.getName()),
				"PARAMETRO_INVALIDO");
	}

	private static String dicaDeFormato(String parametro) {
		if (parametro.equals("competencia")) {
			return " (use AAAA-MM)";
		}
		return parametro.startsWith("data") ? " (use AAAA-MM-DD)" : "";
	}

	@ExceptionHandler(ParametroInvalidoException.class)
	ProblemDetail parametroForaDoPermitido(ParametroInvalidoException e) {
		return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido", e.getMessage(), "PARAMETRO_INVALIDO");
	}

	@ExceptionHandler(SemEscalaVigenteException.class)
	ProblemDetail semEscala(SemEscalaVigenteException e) {
		return problema(HttpStatus.UNPROCESSABLE_CONTENT, "Sem escala para analisar", e.getMessage(),
				"SEM_ESCALA_VIGENTE");
	}

	@ExceptionHandler(AnaliseNaoEncontradaException.class)
	ProblemDetail analiseNaoEncontrada(AnaliseNaoEncontradaException e) {
		return problema(HttpStatus.NOT_FOUND, "Análise não encontrada", e.getMessage(), "ANALISE_NAO_ENCONTRADA");
	}

	@ExceptionHandler(ImportacaoNaoEncontradaException.class)
	ProblemDetail naoEncontrada(ImportacaoNaoEncontradaException e) {
		return problema(HttpStatus.NOT_FOUND, "Importação não encontrada", e.getMessage(), "IMPORTACAO_NAO_ENCONTRADA");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail corpoInvalido(MethodArgumentNotValidException e) {
		var campo = e.getFieldError();
		var detalhe = campo == null ? "requisição inválida" : "'" + campo.getField() + "' " + campo.getDefaultMessage();
		return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido", detalhe, "PARAMETRO_INVALIDO");
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail corpoIlegivel(HttpMessageNotReadableException e) {
		return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido", "corpo da requisição ausente ou malformado",
				"PARAMETRO_INVALIDO");
	}

	@ExceptionHandler(ConflitoUsuarioException.class)
	ProblemDetail conflitoUsuario(ConflitoUsuarioException e) {
		return problema(HttpStatus.CONFLICT, "Operação recusada", e.getMessage(), e.getCodigo());
	}

	@ExceptionHandler(UsuarioNaoEncontradoException.class)
	ProblemDetail usuarioNaoEncontrado(UsuarioNaoEncontradoException e) {
		return problema(HttpStatus.NOT_FOUND, "Usuário não encontrado", e.getMessage(), "USUARIO_NAO_ENCONTRADO");
	}

	@ExceptionHandler(CredenciaisInvalidasException.class)
	ProblemDetail credenciaisInvalidas(CredenciaisInvalidasException e) {
		return problema(HttpStatus.UNAUTHORIZED, "Login recusado", e.getMessage(), "CREDENCIAIS_INVALIDAS");
	}

	/** Sem token, ou token inválido/expirado (vem dos filtros do Spring Security). */
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> naoAutenticado(AuthenticationException e) {
		var detalhe = e instanceof OAuth2AuthenticationException
				? "sessão expirada ou inválida; faça login novamente"
				: "faça login para acessar a API";
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(problema(HttpStatus.UNAUTHORIZED, "Não autenticado", detalhe, "NAO_AUTENTICADO"));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail acessoNegado(AccessDeniedException e) {
		return problema(HttpStatus.FORBIDDEN, "Acesso negado", "seu perfil não permite esta operação",
				"ACESSO_NEGADO");
	}

	private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe, String codigo) {
		var problema = ProblemDetail.forStatusAndDetail(status, detalhe);
		problema.setTitle(titulo);
		problema.setProperty("codigo", codigo);
		return problema;
	}
}
