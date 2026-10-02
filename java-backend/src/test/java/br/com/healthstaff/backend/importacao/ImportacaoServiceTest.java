package br.com.healthstaff.backend.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.healthstaff.backend.IntegracaoComBanco;
import br.com.healthstaff.backend.bancohoras.SaldoBancoHorasRepository;
import br.com.healthstaff.backend.colaborador.Colaborador;
import br.com.healthstaff.backend.colaborador.ColaboradorRepository;
import br.com.healthstaff.backend.escala.EscalaRepository;
import br.com.healthstaff.backend.escala.PlantaoRepository;
import br.com.healthstaff.backend.escala.SetorRepository;
import br.com.healthstaff.backend.extrator.contrato.LoteImportacao;
import br.com.healthstaff.backend.extrator.contrato.ResultadoExtracao;
import br.com.healthstaff.backend.extrator.contrato.SaldoBancoHoras;
import br.com.healthstaff.backend.extrator.contrato.TipoMarcacao;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Persistência dos lotes do extrator num Postgres 18 real. Os lotes vêm da
 * resposta-exemplo gerada pela própria API Python (dados fictícios):
 * escala com VANIA (matrícula 90002, folguista) e RECEM CONTRATADA (fora do
 * Two); banco de horas com 5 colaboradores (90001-90005).
 */
@SpringBootTest
class ImportacaoServiceTest extends IntegracaoComBanco {

	@Autowired
	ImportacaoService servico;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transacao;

	@Autowired
	ImportacaoRepository importacoes;

	@Autowired
	InconsistenciaImportacaoRepository inconsistencias;

	@Autowired
	ColaboradorRepository colaboradores;

	@Autowired
	SetorRepository setores;

	@Autowired
	EscalaRepository escalas;

	@Autowired
	PlantaoRepository plantoes;

	@Autowired
	SaldoBancoHorasRepository saldos;

	@BeforeEach
	void limparBanco() {
		jdbc.execute("""
				truncate table plantao, escala, saldo_banco_horas, inconsistencia, importacao, colaborador, setor
				restart identity cascade""");
	}

	ResultadoExtracao exemplo() {
		try {
			var json = new ClassPathResource("contrato/resultado-extracao.exemplo.json")
					.getContentAsString(StandardCharsets.UTF_8);
			return jsonMapper.readValue(json, ResultadoExtracao.class);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	LoteImportacao escala() {
		return exemplo().getEscala();
	}

	LoteImportacao bancoHoras() {
		return exemplo().getBancoHoras();
	}

	/** Mesmo conteúdo, outro arquivo (nova exportação). */
	static LoteImportacao comoOutroArquivo(LoteImportacao lote) {
		lote.setIdLote(UUID.randomUUID());
		lote.getOrigem().setHashSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
		return lote;
	}

	static int marcacoes(LoteImportacao lote) {
		return lote.getSetores().stream()
				.flatMap(s -> s.getColaboradores().stream())
				.mapToInt(c -> c.getPlantoes().size())
				.sum();
	}

	Colaborador colaborador(String nome) {
		return colaboradores.findAll().stream().filter(c -> c.getNome().equals(nome)).findFirst().orElseThrow();
	}

	@Nested
	class Escala {

		@Test
		void registraImportacaoEscalaEPlantoes() {
			var lote = escala();

			var resultado = servico.registrar(lote);

			assertThat(resultado.id()).isEqualTo(lote.getIdLote());
			assertThat(resultado.duplicada()).isFalse();
			assertThat(resultado.escalas()).singleElement().satisfies(versao -> {
				assertThat(versao.setor()).isEqualTo("UTI PEDIÁTRICA");
				assertThat(versao.competencia()).isEqualTo(LocalDate.of(2026, 10, 1));
				assertThat(versao.versao()).isEqualTo(1);
				assertThat(versao.marcacoes()).isEqualTo(marcacoes(lote));
			});

			var importacao = importacoes.findById(lote.getIdLote()).orElseThrow();
			assertThat(importacao.getTipoDocumento()).isEqualTo("ESCALA_EXCEL");
			assertThat(importacao.getHashSha256()).isEqualTo(lote.getOrigem().getHashSha256());

			var setor = setores.findByChave("UTI PEDIATRICA").orElseThrow();
			var vigente = escalas.findBySetorAndCompetenciaAndVigenteTrue(setor, LocalDate.of(2026, 10, 1)).orElseThrow();
			assertThat(plantoes.countByEscala(vigente)).isEqualTo(marcacoes(lote));
		}

		@Test
		void plantaoNoturnoGravadoSemDeslocarFuso() {
			servico.registrar(escala());

			transacao.executeWithoutResult(status -> {
				var vania = colaborador("VANIA FICTICIA ROCHA");
				var noturno = plantoes.findAll().stream()
						.filter(p -> p.getColaborador().getId().equals(vania.getId()))
						.filter(p -> p.getData().equals(LocalDate.of(2026, 10, 1)))
						.findFirst().orElseThrow();
				assertThat(noturno.getTipo()).isEqualTo("PLANTAO");
				assertThat(noturno.getTurno()).isEqualTo("NOTURNO_12H");
				assertThat(noturno.getInicio()).isEqualTo(LocalDateTime.of(2026, 10, 1, 19, 0));
				assertThat(noturno.getFim()).isEqualTo(LocalDateTime.of(2026, 10, 2, 7, 0));
				assertThat(noturno.getSetor().getNome()).isEqualTo("UTI PEDIÁTRICA");
				assertThat(noturno.isFolguista()).isTrue();
			});
		}

		@Test
		void marcacoesSemHorarioEObservacao() {
			servico.registrar(escala());

			var porTipo = jdbc.queryForList("select tipo, count(*) as n from plantao group by tipo order by tipo");
			assertThat(porTipo).extracting(linha -> linha.get("tipo"))
					.contains("PLANTAO", "FOLGA", "DESCONHECIDO");
			var bh = jdbc.queryForMap("select codigo_original, observacao, inicio from plantao where codigo_original = 'BH'");
			assertThat(bh.get("observacao")).isEqualTo("compensação de banco de horas");
			assertThat(bh.get("inicio")).isNull();
		}

		@Test
		void inconsistenciasGuardadasParaRevisao() {
			var lote = escala();

			var resultado = servico.registrar(lote);

			var gravadas = inconsistencias.findByImportacaoIdOrderById(resultado.id());
			assertThat(gravadas).hasSize(lote.getInconsistencias().size());
			assertThat(gravadas).extracting(InconsistenciaImportacao::getCodigo)
					.contains("COLABORADOR_NAO_RECONHECIDO", "CODIGO_TURNO_DESCONHECIDO");
			assertThat(gravadas).filteredOn(i -> i.getCodigo().equals("COLABORADOR_NAO_RECONHECIDO"))
					.singleElement()
					.satisfies(i -> {
						assertThat(i.getNomeColaborador()).isEqualTo("RECEM CONTRATADA");
						assertThat(i.getAba()).isEqualTo("Escala");
						assertThat(i.getLinha()).isNotNull();
					});
		}
	}

	@Nested
	class Versionamento {

		@Test
		void mesmoArquivoDuasVezesNaoDuplica() {
			var primeira = servico.registrar(escala());
			var segunda = servico.registrar(comoOutroIdMesmoArquivo(escala()));

			assertThat(segunda.duplicada()).isTrue();
			assertThat(segunda.id()).isEqualTo(primeira.id());
			assertThat(importacoes.count()).isEqualTo(1);
			assertThat(escalas.count()).isEqualTo(1);
			assertThat(plantoes.count()).isEqualTo(marcacoes(escala()));
		}

		/** O extrator gera um idLote novo a cada chamada; o hash do arquivo é o mesmo. */
		LoteImportacao comoOutroIdMesmoArquivo(LoteImportacao lote) {
			lote.setIdLote(UUID.randomUUID());
			return lote;
		}

		@Test
		void novaEscalaDoMesmoMesViraVersaoVigenteEGuardaAAnterior() {
			var v1 = servico.registrar(escala());
			var v2 = servico.registrar(comoOutroArquivo(escala()));

			assertThat(v2.escalas()).singleElement().extracting(versao -> versao.versao()).isEqualTo(2);
			var setor = setores.findByChave("UTI PEDIATRICA").orElseThrow();
			var versoes = escalas.findBySetorAndCompetenciaOrderByVersao(setor, LocalDate.of(2026, 10, 1));
			assertThat(versoes).extracting(e -> e.getVersao(), e -> e.isVigente(), e -> e.getImportacaoId())
					.containsExactly(
							org.assertj.core.groups.Tuple.tuple(1, false, v1.id()),
							org.assertj.core.groups.Tuple.tuple(2, true, v2.id()));
			// Histórico preservado: os plantões da versão 1 continuam lá.
			assertThat(plantoes.countByEscala(versoes.getFirst())).isEqualTo(marcacoes(escala()));
			// Pessoas não são duplicadas entre versões.
			assertThat(colaboradores.count()).isEqualTo(2);
		}

		@Test
		void outroMesNaoAfetaAVersaoVigente() {
			servico.registrar(escala());
			var novembro = comoOutroArquivo(escala());
			novembro.setPeriodoInicio(LocalDate.of(2026, 11, 1));
			novembro.setPeriodoFim(LocalDate.of(2026, 11, 30));
			novembro.getSetores().forEach(s -> s.getColaboradores().forEach(c -> c.getPlantoes().clear()));

			var resultado = servico.registrar(novembro);

			assertThat(resultado.escalas()).singleElement().extracting(v -> v.versao()).isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from escala where vigente", Integer.class)).isEqualTo(2);
		}
	}

	@Nested
	class BancoHoras {

		@Test
		void registraSaldos() {
			var resultado = servico.registrar(bancoHoras());

			assertThat(resultado.saldos()).isEqualTo(5);
			assertThat(saldos.findByImportacaoId(resultado.id())).hasSize(5);
			transacao.executeWithoutResult(status -> {
				var yara = colaboradores.findByMatricula("90004").orElseThrow();
				var saldo = saldos.findFirstByColaboradorOrderByDataReferenciaDescIdDesc(yara).orElseThrow();
				assertThat(saldo.getMinutos()).isEqualTo(-243);
				assertThat(saldo.getDataReferencia()).isEqualTo(LocalDate.of(2026, 10, 1));
				assertThat(saldo.getInicioApuracao()).isEqualTo(LocalDate.of(2025, 5, 16));
				assertThat(saldo.getDepartamento()).isEqualTo("HX - UTI NEONATAL");
			});
		}

		@Test
		void novoRelatorioGuardaHistoricoESaldoMaisRecenteVale() {
			servico.registrar(bancoHoras());
			var outubro15 = comoOutroArquivo(bancoHoras());
			outubro15.setPeriodoInicio(LocalDate.of(2026, 10, 15));
			outubro15.setPeriodoFim(LocalDate.of(2026, 10, 15));
			outubro15.getSetores().forEach(s -> s.getColaboradores().forEach(c -> c.getSaldoBancoHoras()
					.dataReferencia(LocalDate.of(2026, 10, 15)).minutos(c.getSaldoBancoHoras().getMinutos() + 60)));
			servico.registrar(outubro15);

			assertThat(saldos.count()).isEqualTo(10);
			transacao.executeWithoutResult(status -> {
				var yara = colaboradores.findByMatricula("90004").orElseThrow();
				assertThat(saldos.findFirstByColaboradorOrderByDataReferenciaDescIdDesc(yara).orElseThrow().getMinutos())
						.isEqualTo(-183);
			});
		}
	}

	@Nested
	class Colaboradores {

		@Test
		void foraDoTwoViraCadastroProvisorio() {
			servico.registrar(escala());

			var recem = colaborador("RECEM CONTRATADA");
			assertThat(recem.isProvisorio()).isTrue();
			assertThat(recem.getMatricula()).isNull();
			assertThat(jdbc.queryForObject(
					"select count(*) from plantao where colaborador_id = ?", Integer.class, recem.getId()))
					.isEqualTo(31);
		}

		@Test
		void provisorioEhPromovidoQuandoAMatriculaAparece() {
			servico.registrar(escala());
			var provisorio = colaborador("RECEM CONTRATADA");

			// Relatório seguinte do Two já traz a recém-contratada.
			var bh = comoOutroArquivo(bancoHoras());
			bh.getSetores().getFirst().addColaboradoresItem(
					// nomeNormalizado é calculado pelo extrator (somente leitura no contrato).
					new br.com.healthstaff.backend.extrator.contrato.Colaborador("RECEM CONTRATADA")
							.matricula("90009")
							.nome("RECEM CONTRATADA")
							.saldoBancoHoras(new SaldoBancoHoras().minutos(30).dataReferencia(LocalDate.of(2026, 10, 1))));
			servico.registrar(bh);

			var promovido = colaboradores.findByMatricula("90009").orElseThrow();
			assertThat(promovido.getId()).isEqualTo(provisorio.getId());
			assertThat(promovido.isProvisorio()).isFalse();
			assertThat(colaboradores.findAll()).filteredOn(c -> c.getNome().equals("RECEM CONTRATADA")).hasSize(1);
			// Os plantões da escala continuam ligados à mesma pessoa.
			assertThat(jdbc.queryForObject(
					"select count(*) from plantao where colaborador_id = ?", Integer.class, promovido.getId()))
					.isEqualTo(31);
		}

		@Test
		void escalaSemMatriculaUsaCadastroDoTwoDeMesmoNome() {
			servico.registrar(bancoHoras());
			var escala = escala();
			escala.getSetores().forEach(s -> s.getColaboradores().forEach(c -> c.setMatricula(null)));

			servico.registrar(escala);

			var vania = colaboradores.findByMatricula("90002").orElseThrow();
			assertThat(jdbc.queryForObject(
					"select count(*) from plantao where colaborador_id = ?", Integer.class, vania.getId()))
					.isEqualTo(31);
			assertThat(colaboradores.findAll()).filteredOn(Colaborador::isProvisorio)
					.extracting(Colaborador::getNome)
					.containsExactly("RECEM CONTRATADA");
		}

		@Test
		void homonimosNaoSaoAdivinhados() {
			var bh = bancoHoras();
			var setor = bh.getSetores().getFirst();
			for (var matricula : new String[] { "70001", "70002" }) {
				setor.addColaboradoresItem(new br.com.healthstaff.backend.extrator.contrato.Colaborador("ANA HOMONIMA")
						.matricula(matricula).nome("ANA HOMONIMA")
						.saldoBancoHoras(new SaldoBancoHoras().minutos(0).dataReferencia(LocalDate.of(2026, 10, 1))));
			}
			servico.registrar(bh);
			var escala = escala();
			var pessoas = escala.getSetores().getFirst().getColaboradores();
			pessoas.set(0, new br.com.healthstaff.backend.extrator.contrato.Colaborador("ANA HOMONIMA")
					.nome("ANA HOMONIMA").plantoes(pessoas.getFirst().getPlantoes()));

			servico.registrar(escala);

			assertThat(colaboradores.findAll()).filteredOn(c -> c.getNome().equals("ANA HOMONIMA"))
					.extracting(Colaborador::getMatricula)
					.containsExactlyInAnyOrder("70001", "70002", null);
		}

		@Test
		void fontesSeComplementamSemApagarDados() {
			servico.registrar(escala());
			servico.registrar(bancoHoras());

			var vania = colaboradores.findByMatricula("90002").orElseThrow();
			// Cargo, COREN e jornada vêm só da escala; o Two não os apaga.
			assertThat(vania.getCargo()).isEqualTo("Enfª Folguista Diurna");
			assertThat(vania.getRegistroConselho()).isEqualTo("100007");
			assertThat(vania.getJornada()).isEqualTo("12x36");
			assertThat(colaboradores.count()).isEqualTo(6); // 5 do Two + RECEM CONTRATADA
		}
	}

	@Nested
	class Atomicidade {

		@Test
		void falhaNoMeioNaoDeixaImportacaoPelaMetade() {
			var lote = escala();
			// Plantão sem horário viola o CHECK do banco no meio da gravação.
			var ultimo = lote.getSetores().getFirst().getColaboradores().getLast().getPlantoes();
			ultimo.add(new br.com.healthstaff.backend.extrator.contrato.Plantao()
					.data(LocalDate.of(2026, 10, 31)).tipo(TipoMarcacao.PLANTAO).codigoOriginal("PD"));

			assertThatThrownBy(() -> servico.registrar(lote))
					.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

			assertThat(importacoes.count()).isZero();
			assertThat(inconsistencias.count()).isZero();
			assertThat(escalas.count()).isZero();
			assertThat(plantoes.count()).isZero();
			assertThat(colaboradores.count()).isZero();
		}
	}
}
