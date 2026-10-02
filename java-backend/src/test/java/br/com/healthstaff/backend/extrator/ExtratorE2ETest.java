package br.com.healthstaff.backend.extrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import br.com.healthstaff.backend.extrator.contrato.Colaborador;
import br.com.healthstaff.backend.extrator.contrato.TipoDocumento;

import br.com.healthstaff.backend.IntegracaoComBanco;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;

/**
 * Ponta a ponta contra o python-extractor de verdade. Só roda com o serviço no ar:
 *
 * <pre>
 * docker compose up -d                       (na raiz; ou uvicorn main:app)
 * EXTRACTOR_E2E_URL=http://127.0.0.1:8000 ./mvnw test -Dtest=ExtratorE2ETest
 * </pre>
 *
 * Se o extrator exigir chave, informe também EXTRACTOR_E2E_API_KEY.
 */
@EnabledIfEnvironmentVariable(named = "EXTRACTOR_E2E_URL", matches = ".+")
@SpringBootTest(properties = {
		"healthstaff.extrator.url=${EXTRACTOR_E2E_URL}",
		"healthstaff.extrator.api-key=${EXTRACTOR_E2E_API_KEY:}" })
class ExtratorE2ETest extends IntegracaoComBanco {

	/** Relatório de Banco de Horas do Two em CSV, com dados fictícios. */
	static final String BANCO_HORAS_CSV = """
			Matrícula;Colaborador;Gestor;Departamento;Empregador;Times;Data de início;Saldo;Saldo
			90001;TEREZA FICTICIA ALVES;GESTORA EXEMPLO;UTI PEDIATRICA HOSPITAL EXEMPLO;59 - HOSPITAL EXEMPLO;;01/01/24;44:42;44,700
			90004;YARA FICTICIA BRAGA;GESTORA EXEMPLO;HX - UTI NEONATAL;59 - HOSPITAL EXEMPLO;;16/05/25;-04:03;-4,050
			""";

	@Autowired
	ExtratorClient cliente;

	@Autowired
	ExtratorHealthIndicator saude;

	static ArquivoParaExtracao csv(String nome) {
		return new ArquivoParaExtracao(nome, new ByteArrayResource(BANCO_HORAS_CSV.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void extratorNoAr() {
		assertThat(saude.health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void extraiBancoDeHorasEmCsv() {
		var resultado = cliente.extrair(
				SolicitacaoExtracao.somenteBancoHoras(csv("banco-horas.csv"), LocalDate.of(2026, 10, 1)));

		assertThat(resultado.getEscala()).isNull();
		var lote = resultado.getBancoHoras();
		assertThat(lote.getOrigem().getTipoDocumento()).isEqualTo(TipoDocumento.PONTO_CSV);
		assertThat(lote.getOrigem().getNomeArquivo()).isEqualTo("banco-horas.csv");
		assertThat(lote.getPeriodoInicio()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(lote.getSetores().stream().flatMap(s -> s.getColaboradores().stream()))
				.extracting(Colaborador::getMatricula, c -> c.getSaldoBancoHoras().getMinutos())
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("90001", 2682),
						org.assertj.core.groups.Tuple.tuple("90004", -243));
		assertThat(lote.getInconsistencias()).isEmpty();
	}

	@Test
	void arquivoNoCampoErradoEhRecusadoComMotivo() {
		var solicitacao = SolicitacaoExtracao.somenteEscala(csv("banco-horas.csv"));

		assertThatThrownBy(() -> cliente.extrair(solicitacao))
				.isInstanceOfSatisfying(ExtracaoRejeitadaException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(422);
					assertThat(e.getErro().getCampo()).isEqualTo("escala");
					assertThat(e.getErro().getArquivo()).isEqualTo("banco-horas.csv");
				});
	}
}
