package br.com.healthstaff.backend.regras;

import java.time.YearMonth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.healthstaff.backend.regras.Analise.Gatilho;
import br.com.healthstaff.backend.regras.AnaliseConsulta.DetalheAnalise;
import br.com.healthstaff.backend.regras.MotorRegrasService.ResumoAnalise;

/**
 * Análises do motor de regras por competência ({@code AAAA-MM}).
 * As importações já disparam a análise; o POST serve para reprocessar
 * (ex.: depois de mudar parâmetros em {@code healthstaff.regras}).
 */
@RestController
@RequestMapping("/api/v1/analises")
class AnaliseController {

	private final MotorRegrasService motor;
	private final AnaliseConsulta consulta;

	AnaliseController(MotorRegrasService motor, AnaliseConsulta consulta) {
		this.motor = motor;
		this.consulta = consulta;
	}

	@GetMapping("/{competencia}")
	DetalheAnalise vigente(@PathVariable YearMonth competencia) {
		return consulta.vigente(competencia);
	}

	@PostMapping("/{competencia}")
	@ResponseStatus(HttpStatus.CREATED)
	ResumoAnalise reprocessar(@PathVariable YearMonth competencia) {
		return motor.analisar(competencia, Gatilho.MANUAL, null)
				.orElseThrow(() -> new SemEscalaVigenteException(competencia));
	}
}
