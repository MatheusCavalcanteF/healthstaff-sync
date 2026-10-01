"""Associação de nomes brutos extraídos dos PDFs ao cadastro oficial."""

import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from enum import StrEnum

from rapidfuzz import fuzz, process

from models import LoteImportacao, normalizar_nome

PARTICULAS = frozenset({"DA", "DE", "DO", "DAS", "DOS", "E", "D"})


def chave_nome(nome: str) -> str:
    """Forma canônica para comparação: sem acento, pontuação e partículas."""
    texto = re.sub(r"[^A-Z ]", " ", normalizar_nome(nome))
    return " ".join(t for t in texto.split() if t not in PARTICULAS)


def pontuar(a: str, b: str, **_) -> float:
    """Média de token_sort (penaliza nomes incompletos) e token_set
    (tolera nome do meio omitido). Um primeiro nome isolado fica ~70."""
    return (fuzz.token_sort_ratio(a, b) + fuzz.token_set_ratio(a, b)) / 2


@dataclass(frozen=True)
class CadastroColaborador:
    matricula: str
    nome: str


class StatusMatch(StrEnum):
    EXATO = "EXATO"
    CONFIAVEL = "CONFIAVEL"
    AMBIGUO = "AMBIGUO"
    NAO_ENCONTRADO = "NAO_ENCONTRADO"


@dataclass(frozen=True)
class ResultadoMatch:
    nome_bruto: str
    status: StatusMatch
    score: float
    colaborador: CadastroColaborador | None = None
    candidatos: tuple[tuple[CadastroColaborador, float], ...] = field(default=())

    @property
    def encontrado(self) -> bool:
        return self.status in (StatusMatch.EXATO, StatusMatch.CONFIAVEL)

    def descrever_candidatos(self) -> str:
        return ", ".join(
            f"{c.nome} ({c.matricula}, {score:.0f})" for c, score in self.candidatos
        )


class ColaboradorMatcher:
    """Encontra o colaborador do cadastro mais parecido com um nome bruto.

    - ``EXATO``: nomes idênticos após normalização (score 100).
    - ``CONFIAVEL``: melhor score >= ``limiar`` e sem concorrente próximo.
    - ``AMBIGUO``: dois ou mais candidatos >= ``limiar`` separados por menos
      de ``margem_ambiguidade`` pontos (ex.: homônimos).
    - ``NAO_ENCONTRADO``: melhor score abaixo do ``limiar``.

    Apenas os dois primeiros status devem ser usados para preencher
    matrícula automaticamente; os demais exigem revisão humana.
    """

    def __init__(
        self,
        cadastro: Iterable[CadastroColaborador | tuple[str, str]],
        limiar: float = 85.0,
        margem_ambiguidade: float = 5.0,
        max_candidatos: int = 3,
    ) -> None:
        if not 0 < limiar <= 100:
            raise ValueError("limiar deve estar em (0, 100]")
        self.limiar = limiar
        self.margem_ambiguidade = margem_ambiguidade
        self.max_candidatos = max_candidatos

        self._colaboradores: list[CadastroColaborador] = []
        self._por_matricula: dict[str, CadastroColaborador] = {}
        for item in cadastro:
            colaborador = (
                item if isinstance(item, CadastroColaborador) else CadastroColaborador(*item)
            )
            if colaborador.matricula in self._por_matricula:
                raise ValueError(f"matrícula duplicada no cadastro: {colaborador.matricula}")
            self._por_matricula[colaborador.matricula] = colaborador
            self._colaboradores.append(colaborador)
        self._chaves = [chave_nome(c.nome) for c in self._colaboradores]

    @classmethod
    def de_lote(cls, lote: LoteImportacao, **opcoes) -> "ColaboradorMatcher":
        """Cadastro a partir de um lote já extraído (ex.: Banco de Horas do Two)."""
        cadastro = {
            c.matricula: CadastroColaborador(c.matricula, c.nome)
            for s in lote.setores
            for c in s.colaboradores
            if c.matricula
        }
        return cls(cadastro.values(), **opcoes)

    def __len__(self) -> int:
        return len(self._colaboradores)

    def buscar_por_matricula(self, matricula: str) -> CadastroColaborador | None:
        return self._por_matricula.get(matricula.strip())

    def associar(self, nome_bruto: str) -> ResultadoMatch:
        chave = chave_nome(nome_bruto)
        if not chave or not self._colaboradores:
            return ResultadoMatch(nome_bruto, StatusMatch.NAO_ENCONTRADO, 0.0)

        exatos = [c for c, k in zip(self._colaboradores, self._chaves) if k == chave]
        if len(exatos) == 1:
            return ResultadoMatch(
                nome_bruto, StatusMatch.EXATO, 100.0, exatos[0], ((exatos[0], 100.0),)
            )

        resultados = process.extract(
            chave,
            self._chaves,
            scorer=pontuar,
            limit=max(self.max_candidatos, 2),
        )
        candidatos = tuple(
            (self._colaboradores[indice], round(score, 2))
            for _, score, indice in resultados
        )[: self.max_candidatos]
        melhor, melhor_score = candidatos[0]

        if melhor_score < self.limiar:
            return ResultadoMatch(
                nome_bruto, StatusMatch.NAO_ENCONTRADO, melhor_score, None, candidatos
            )

        segundo_score = resultados[1][1] if len(resultados) > 1 else 0.0
        if len(exatos) > 1 or (
            segundo_score >= self.limiar
            and melhor_score - segundo_score < self.margem_ambiguidade
        ):
            return ResultadoMatch(
                nome_bruto, StatusMatch.AMBIGUO, melhor_score, None, candidatos
            )

        return ResultadoMatch(
            nome_bruto, StatusMatch.CONFIAVEL, melhor_score, melhor, candidatos
        )
