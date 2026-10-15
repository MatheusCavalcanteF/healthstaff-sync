"""Contrato da API versionado para o java-backend.

- ``schema/resultado-extracao.schema.json``: JSON Schema de
  ``ResultadoExtracao`` (inclui ``LoteImportacao`` e todos os tipos
  aninhados), em camelCase e com os campos calculados, como trafegam no JSON.
- ``schema/openapi.json``: documento OpenAPI completo (endpoint, erros e os
  mesmos tipos). É a fonte da geração das classes Java (openapi-generator no
  ``java-backend/pom.xml``).

Para atualizar os arquivos versionados após mudar o contrato:

    python -m api.schema
"""

import json
import sys
from pathlib import Path

from models import ResultadoExtracao

PASTA_SCHEMA = Path(__file__).resolve().parent.parent / "schema"
ARQUIVO_SCHEMA = PASTA_SCHEMA / "resultado-extracao.schema.json"
ARQUIVO_OPENAPI = PASTA_SCHEMA / "openapi.json"


def gerar_schema() -> dict:
    schema = ResultadoExtracao.model_json_schema(by_alias=True, mode="serialization")
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", **schema}


def schema_formatado() -> str:
    return json.dumps(gerar_schema(), indent=2, ensure_ascii=False) + "\n"


def openapi_formatado() -> str:
    from .app import create_app  # import tardio: app importa este módulo

    # Sem chave e com limite fixo: o documento não depende do ambiente.
    documento = create_app(api_key="", tamanho_maximo_mb=20, ambiente="desenvolvimento").openapi()
    return json.dumps(documento, indent=2, ensure_ascii=False) + "\n"


def _gravar(destino: Path, conteudo: str) -> None:
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(conteudo, encoding="utf-8", newline="\n")
    print(f"gravado: {destino}")


if __name__ == "__main__":
    pasta = Path(sys.argv[1]) if len(sys.argv) > 1 else PASTA_SCHEMA
    _gravar(pasta / ARQUIVO_SCHEMA.name, schema_formatado())
    _gravar(pasta / ARQUIVO_OPENAPI.name, openapi_formatado())
