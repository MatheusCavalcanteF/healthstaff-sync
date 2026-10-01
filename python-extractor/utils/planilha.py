"""Leitura de planilhas (Excel e CSV) como grades de texto.

Cada célula vira ``str`` no mesmo formato que a extração de PDF produziria,
para que os extratores tratem as duas origens igualmente:

- inteiros gravados como float (``1.0``, ``100001.0``) -> ``"1"``, ``"100001"``
- datas -> ``dd/mm/aaaa``; horas -> ``HH:MM``; durações -> ``[-]H:MM``
- vazio/``None`` -> ``""``

Células mescladas mantêm o valor apenas na primeira célula (comportamento do
openpyxl), igual a uma célula mesclada desenhada num PDF.
"""

import csv
import io
import warnings
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path

import openpyxl


@dataclass(frozen=True)
class Aba:
    nome: str
    linhas: list[list[str]]  # linhas[i] é a linha i + 1 da planilha

    @property
    def texto(self) -> str:
        return "\n".join(" ".join(c for c in linha if c) for linha in self.linhas)


def texto_celula(valor: object) -> str:
    if valor is None:
        return ""
    if isinstance(valor, bool):
        return str(valor)
    if isinstance(valor, float):
        return str(int(valor)) if valor.is_integer() else str(valor)
    if isinstance(valor, datetime):
        if valor.time() == time(0):
            return f"{valor:%d/%m/%Y}"
        return f"{valor:%d/%m/%Y %H:%M}"
    if isinstance(valor, date):
        return f"{valor:%d/%m/%Y}"
    if isinstance(valor, time):
        return f"{valor:%H:%M}"
    if isinstance(valor, timedelta):
        minutos = round(valor.total_seconds() / 60)
        sinal = "-" if minutos < 0 else ""
        horas, minutos = divmod(abs(minutos), 60)
        return f"{sinal}{horas}:{minutos:02d}"
    return str(valor)


def ler_planilha(caminho: str | Path) -> list[Aba]:
    """Lê todas as abas (valores calculados, não fórmulas)."""
    with warnings.catch_warnings():
        # Exportações do Two não têm estilo padrão; o openpyxl só avisa e segue.
        warnings.filterwarnings("ignore", message="Workbook contains no default style")
        livro = openpyxl.load_workbook(caminho, read_only=True, data_only=True)
    try:
        abas = []
        for planilha in livro.worksheets:
            linhas = [
                [texto_celula(valor) for valor in linha]
                for linha in planilha.iter_rows(values_only=True)
            ]
            largura = max((len(linha) for linha in linhas), default=0)
            abas.append(Aba(planilha.title, [l + [""] * (largura - len(l)) for l in linhas]))
        return abas
    finally:
        livro.close()


CODIFICACOES_CSV = ("utf-8-sig", "cp1252", "latin-1")
SEPARADORES_CSV = (";", "	", ",", "|")


def decodificar(conteudo: bytes) -> str:
    """UTF-8 (com ou sem BOM) ou Windows-1252, comuns em exportações brasileiras."""
    for codificacao in CODIFICACOES_CSV:
        try:
            return conteudo.decode(codificacao)
        except UnicodeDecodeError:
            continue
    raise AssertionError("latin-1 decodifica qualquer sequência de bytes")


def ler_csv(caminho: str | Path) -> list[Aba]:
    """Lê o CSV uma vez por separador candidato (";", tab, ",", "|").

    O separador não é adivinhado aqui: cada interpretação vira uma ``Aba``
    sem nome, e o extrator fica com a primeira em que reconhecer o cabeçalho.
    Assim um ";" com decimais "44,700" não é confundido com separador ",".
    """
    texto = decodificar(Path(caminho).read_bytes())
    abas = []
    for separador in SEPARADORES_CSV:
        linhas = list(csv.reader(io.StringIO(texto), delimiter=separador))
        largura = max((len(linha) for linha in linhas), default=0)
        abas.append(Aba("", [l + [""] * (largura - len(l)) for l in linhas]))
    return abas
