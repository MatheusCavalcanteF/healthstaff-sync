"""Validação contra as exportações reais (não versionadas: contêm dados pessoais).

Os testes são pulados quando os arquivos não estão em ``python-extractor/``.
Só verificam invariantes estruturais; nenhum dado pessoal fica no código.
"""

from datetime import date
from pathlib import Path

import pytest

from extractors import ExtratorNaoEncontrado, GridExtractor, PontoExcelExtractor, criar_registry_padrao
from models import CodigoInconsistencia, Severidade, TipoMarcacao
from utils.matcher import ColaboradorMatcher

RAIZ = Path(__file__).resolve().parent.parent
ESCALA = RAIZ / "ESCALA OUTUBRO 2026.xlsx"
BANCO_HORAS = RAIZ / "TWO - Banco de Horas.xlsx"
APONTAMENTOS = RAIZ / "TWO - Apontamentos.xlsx"
ANALITICO = RAIZ / "Banco de Horas Analítico.xlsx"
ESPELHO = RAIZ / "Espelho de ponto.pdf"


def exige(*arquivos: Path):
    faltando = [a.name for a in arquivos if not a.exists()]
    return pytest.mark.skipif(bool(faltando), reason=f"arquivos reais ausentes: {faltando}")


def codigos(lote) -> set[CodigoInconsistencia]:
    return {i.codigo for i in lote.inconsistencias}


@pytest.fixture(scope="module")
def lote_banco_horas():
    return PontoExcelExtractor(data_referencia=date(2026, 10, 1)).extrair(BANCO_HORAS)


@pytest.fixture(scope="module")
def lote_escala(lote_banco_horas):
    return GridExtractor(matcher=ColaboradorMatcher.de_lote(lote_banco_horas)).extrair(ESCALA)


@exige(BANCO_HORAS)
def test_banco_de_horas_real(lote_banco_horas):
    colaboradores = [c for s in lote_banco_horas.setores for c in s.colaboradores]
    assert len(colaboradores) > 100
    assert all(c.matricula and c.matricula.isdigit() for c in colaboradores)
    assert all(c.saldo_banco_horas is not None for c in colaboradores)
    assert any(c.saldo_banco_horas.minutos < 0 for c in colaboradores)
    assert all(c.saldo_banco_horas.inicio_apuracao for c in colaboradores)
    # Colunas HH:MM e decimal concordam em todas as linhas.
    assert lote_banco_horas.inconsistencias == []


@exige(ESCALA, BANCO_HORAS)
def test_escala_real(lote_escala):
    assert (lote_escala.periodo_inicio, lote_escala.periodo_fim) == (date(2026, 10, 1), date(2026, 10, 31))
    assert [s.nome for s in lote_escala.setores] == ["UTI PEDIÁTRICA"]

    colaboradores = [c for s in lote_escala.setores for c in s.colaboradores]
    assert len(colaboradores) > 30
    assert not any("TOTAL" in c.nome.upper() or "LEGENDA" in c.nome.upper() for c in colaboradores)
    assert any(c.folguista for c in colaboradores)

    # Todas as siglas reconhecidas e o total de plantões de cada linha confere.
    assert CodigoInconsistencia.CODIGO_TURNO_DESCONHECIDO not in codigos(lote_escala)
    assert CodigoInconsistencia.TOTAL_DIVERGENTE not in codigos(lote_escala)
    assert not [i for i in lote_escala.inconsistencias if i.severidade is Severidade.ERRO]

    tipos = {p.tipo for c in colaboradores for p in c.plantoes}
    assert {TipoMarcacao.PLANTAO, TipoMarcacao.FOLGA, TipoMarcacao.FERIAS, TipoMarcacao.LICENCA} <= tipos

    # Quase todos casados com a matrícula do Two pelo nome.
    com_matricula = sum(1 for c in colaboradores if c.matricula)
    assert com_matricula / len(colaboradores) >= 0.9


@exige(ESCALA, BANCO_HORAS)
def test_plantoes_nao_se_sobrepoem(lote_escala):
    for colaborador in (c for s in lote_escala.setores for c in s.colaboradores):
        turnos = sorted(
            (p.inicio, p.fim) for p in colaborador.plantoes if p.tipo is TipoMarcacao.PLANTAO
        )
        for (_, fim), (inicio, _) in zip(turnos, turnos[1:]):
            assert fim <= inicio, colaborador.registro_conselho


@exige(ESCALA, BANCO_HORAS, APONTAMENTOS, ANALITICO)
def test_registry_seleciona_cada_arquivo_real():
    registry = criar_registry_padrao()
    assert isinstance(registry.selecionar(ESCALA), GridExtractor)
    assert isinstance(registry.selecionar(BANCO_HORAS), PontoExcelExtractor)
    for sem_extrator in (APONTAMENTOS, ANALITICO):
        with pytest.raises(ExtratorNaoEncontrado):
            registry.selecionar(sem_extrator)


@exige(ESPELHO)
def test_espelho_de_ponto_sem_texto_nao_e_reconhecido():
    # Impresso pelo navegador como imagem vetorial: não há texto para extrair.
    with pytest.raises(ExtratorNaoEncontrado):
        criar_registry_padrao().selecionar(ESPELHO)


@exige(ESCALA, BANCO_HORAS)
def test_cadastro_do_two_nao_descarta_ninguem_da_escala(lote_escala):
    sem_cadastro = GridExtractor().extrair(ESCALA)
    nomes = lambda lote: [c.nome for s in lote.setores for c in s.colaboradores]  # noqa: E731
    assert nomes(lote_escala) == nomes(sem_cadastro)
    assert lote_escala.total_plantoes == sem_cadastro.total_plantoes

    fora_do_two = [c for s in lote_escala.setores for c in s.colaboradores if not c.matricula]
    avisos = {
        i.nome_colaborador
        for i in lote_escala.inconsistencias
        if i.codigo is CodigoInconsistencia.COLABORADOR_NAO_RECONHECIDO
    }
    assert fora_do_two and {c.nome for c in fora_do_two} == avisos
