import pytest

from utils.matcher import (
    CadastroColaborador,
    ColaboradorMatcher,
    StatusMatch,
    chave_nome,
)

CADASTRO = [
    ("1001", "Maria da Silva Oliveira"),
    ("1002", "João Pedro Souza"),
    ("1003", "Ana Paula Costa"),
    ("1004", "Carlos Alberto Nunes"),
]


@pytest.fixture
def matcher() -> ColaboradorMatcher:
    return ColaboradorMatcher(CADASTRO)


def test_chave_nome_remove_acentos_pontuacao_e_particulas():
    assert chave_nome("  João  d'Ávila dos Santos e Silva ") == "JOAO AVILA SANTOS SILVA"


@pytest.mark.parametrize(
    "nome", ["Maria da Silva Oliveira", "MARIA SILVA OLIVEIRA", "maria  da silva oliveira"]
)
def test_match_exato_apos_normalizacao(matcher, nome):
    resultado = matcher.associar(nome)
    assert resultado.status is StatusMatch.EXATO
    assert resultado.score == 100.0
    assert resultado.colaborador.matricula == "1001"
    assert resultado.encontrado


@pytest.mark.parametrize(
    ("nome", "matricula"),
    [
        ("Maria Oliveira", "1001"),  # nome do meio omitido
        ("Maria S. Oliveira", "1001"),  # abreviado
        ("Maria Silva Olivera", "1001"),  # erro de digitação
        ("Oliveira Maria Silva", "1001"),  # ordem trocada
        ("Joao P Souza", "1002"),
        ("Carlos Alberto", "1004"),
    ],
)
def test_match_confiavel(matcher, nome, matricula):
    resultado = matcher.associar(nome)
    assert resultado.status is StatusMatch.CONFIAVEL
    assert resultado.colaborador.matricula == matricula
    assert resultado.score >= matcher.limiar


@pytest.mark.parametrize("nome", ["Maria", "Roberto Lima", "Ana Paula Castro"])
def test_baixa_similaridade_nao_associa(matcher, nome):
    resultado = matcher.associar(nome)
    assert resultado.status is StatusMatch.NAO_ENCONTRADO
    assert resultado.colaborador is None
    assert not resultado.encontrado
    assert resultado.score < matcher.limiar
    assert resultado.candidatos  # sugestões para revisão humana


def test_candidatos_ordenados_e_limitados(matcher):
    resultado = matcher.associar("Ana Castro")
    scores = [score for _, score in resultado.candidatos]
    assert scores == sorted(scores, reverse=True)
    assert len(resultado.candidatos) <= matcher.max_candidatos
    assert resultado.candidatos[0][0].matricula == "1003"


def test_homonimos_exatos_sao_ambiguos():
    matcher = ColaboradorMatcher([("1", "Ana Costa"), ("2", "Ana Costa")])
    resultado = matcher.associar("ANA COSTA")
    assert resultado.status is StatusMatch.AMBIGUO
    assert resultado.colaborador is None
    assert {c.matricula for c, _ in resultado.candidatos} == {"1", "2"}


def test_candidatos_proximos_sao_ambiguos():
    matcher = ColaboradorMatcher([("1", "Maria Silva Oliveira"), ("2", "Maria Souza Oliveira")])
    resultado = matcher.associar("Maria Oliveira")
    assert resultado.status is StatusMatch.AMBIGUO
    assert resultado.colaborador is None
    assert "Maria Silva Oliveira (1" in resultado.descrever_candidatos()
    assert "Maria Souza Oliveira (2" in resultado.descrever_candidatos()


def test_limiar_configuravel():
    estrito = ColaboradorMatcher(CADASTRO, limiar=95)
    assert estrito.associar("Maria Oliveira").status is StatusMatch.NAO_ENCONTRADO


@pytest.mark.parametrize("nome", ["", "   ", "123", "..."])
def test_nome_vazio_ou_sem_letras(matcher, nome):
    resultado = matcher.associar(nome)
    assert resultado.status is StatusMatch.NAO_ENCONTRADO
    assert resultado.score == 0.0


def test_cadastro_vazio():
    assert ColaboradorMatcher([]).associar("Maria").status is StatusMatch.NAO_ENCONTRADO


def test_buscar_por_matricula(matcher):
    assert matcher.buscar_por_matricula(" 1002 ").nome == "João Pedro Souza"
    assert matcher.buscar_por_matricula("9999") is None


def test_aceita_dataclass_ou_tupla():
    matcher = ColaboradorMatcher([CadastroColaborador("1", "Ana"), ("2", "Bia")])
    assert len(matcher) == 2


def test_rejeita_matricula_duplicada_no_cadastro():
    with pytest.raises(ValueError, match="duplicada"):
        ColaboradorMatcher([("1", "Ana"), ("1", "Bia")])


@pytest.mark.parametrize("limiar", [0, -1, 101])
def test_rejeita_limiar_invalido(limiar):
    with pytest.raises(ValueError):
        ColaboradorMatcher(CADASTRO, limiar=limiar)
