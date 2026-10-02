package br.com.healthstaff.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regras garantidas pelo próprio banco (valem mesmo para quem gravar fora da
 * aplicação): testadas com SQL direto, sem passar pelas entidades.
 */
@SpringBootTest
class EsquemaBancoTest extends IntegracaoComBanco {

	@Autowired
	JdbcTemplate jdbc;

	UUID importacao;
	long setor;
	long colaborador;

	@BeforeEach
	void preparar() {
		jdbc.execute("""
				truncate table plantao, escala, saldo_banco_horas, inconsistencia, importacao, colaborador, setor
				restart identity cascade""");
		importacao = UUID.randomUUID();
		inserirImportacao(importacao, "a".repeat(64));
		setor = jdbc.queryForObject("insert into setor (nome, chave) values ('UTI', 'UTI') returning id", Long.class);
		colaborador = jdbc.queryForObject(
				"insert into colaborador (matricula, nome, nome_normalizado) values ('1', 'ANA', 'ANA') returning id",
				Long.class);
	}

	void inserirImportacao(UUID id, String hash) {
		jdbc.update("""
				insert into importacao (id, tipo_documento, nome_arquivo, hash_sha256, tamanho_bytes, extrator,
				    versao_extrator, versao_contrato, periodo_inicio, periodo_fim, gerado_em,
				    total_colaboradores, total_plantoes, possui_erros)
				values (?, 'ESCALA_EXCEL', 'e.xlsx', ?, 1, 'x', '1', '1.0', '2026-10-01', '2026-10-31', now(), 0, 0, false)""",
				id, hash);
	}

	long inserirEscala(int versao, boolean vigente) {
		return jdbc.queryForObject("""
				insert into escala (setor_id, competencia, versao, vigente, importacao_id)
				values (?, '2026-10-01', ?, ?, ?) returning id""", Long.class, setor, versao, vigente, importacao);
	}

	void inserirPlantao(long escala, String tipo, String inicio, String fim) {
		jdbc.update("""
				insert into plantao (escala_id, setor_id, colaborador_id, data, tipo, codigo_original, inicio, fim)
				values (?, ?, ?, '2026-10-01', ?, 'X', ?::timestamp, ?::timestamp)""",
				escala, setor, colaborador, tipo, inicio, fim);
	}

	@Test
	void mesmoArquivoNaoEntraDuasVezes() {
		assertThatThrownBy(() -> inserirImportacao(UUID.randomUUID(), "a".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_importacao_arquivo");
	}

	@Test
	void somenteUmaVersaoVigentePorSetorECompetencia() {
		inserirEscala(1, true);
		inserirEscala(2, false);

		assertThatThrownBy(() -> inserirEscala(3, true))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_escala_vigente");
	}

	@Test
	void competenciaEhSempreOPrimeiroDiaDoMes() {
		assertThatThrownBy(() -> jdbc.update("""
				insert into escala (setor_id, competencia, versao, vigente, importacao_id)
				values (?, '2026-10-15', 1, true, ?)""", setor, importacao))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_escala_competencia");
	}

	@Test
	void plantaoDeTrabalhoExigeHorarioValido() {
		long escala = inserirEscala(1, true);

		inserirPlantao(escala, "PLANTAO", "2026-10-01 19:00", "2026-10-02 07:00");
		inserirPlantao(escala, "FOLGA", null, null);

		assertThatThrownBy(() -> inserirPlantao(escala, "PLANTAO", null, null))
				.hasMessageContaining("ck_plantao_horario");
		assertThatThrownBy(() -> inserirPlantao(escala, "PLANTAO", "2026-10-01 19:00", "2026-10-01 07:00"))
				.hasMessageContaining("ck_plantao_horario");
		assertThatThrownBy(() -> inserirPlantao(escala, "FOLGA", "2026-10-01 07:00", "2026-10-01 19:00"))
				.hasMessageContaining("ck_plantao_horario");
	}

	@Test
	void umCadastroProvisorioPorNome() {
		jdbc.update("insert into colaborador (nome, nome_normalizado) values ('BIA', 'BIA')");

		assertThatThrownBy(() -> jdbc.update("insert into colaborador (nome, nome_normalizado) values ('Bia', 'BIA')"))
				.hasMessageContaining("uk_colaborador_provisorio");
		// Com matrícula, homônimos são permitidos.
		jdbc.update("insert into colaborador (matricula, nome, nome_normalizado) values ('2', 'BIA', 'BIA')");
		jdbc.update("insert into colaborador (matricula, nome, nome_normalizado) values ('3', 'BIA', 'BIA')");
		assertThat(jdbc.queryForObject("select count(*) from colaborador where nome_normalizado = 'BIA'", Integer.class))
				.isEqualTo(3);
	}

	@Test
	void excluirImportacaoLevaInconsistenciasESaldos() {
		jdbc.update("""
				insert into inconsistencia (importacao_id, codigo, severidade, mensagem) values (?, 'OUTRO', 'INFO', 'x')""",
				importacao);
		jdbc.update("""
				insert into saldo_banco_horas (importacao_id, colaborador_id, minutos, data_referencia)
				values (?, ?, 60, '2026-10-01')""", importacao, colaborador);

		jdbc.update("delete from importacao where id = ?", importacao);

		assertThat(jdbc.queryForObject("select count(*) from inconsistencia", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from saldo_banco_horas", Integer.class)).isZero();
	}
}
