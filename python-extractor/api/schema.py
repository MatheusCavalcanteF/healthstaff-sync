"""JSON Schema do contrato de resposta, para o java-backend.

Gera o schema de ``ResultadoExtracao`` (que inclui ``LoteImportacao`` e
todos os tipos aninhados) com os nomes em camelCase e os campos calculados,
exatamente como trafegam no JSON.

Para atualizar o arquivo versionado após mudar o contrato:

    python -m api.schema
"""

import json
import sys
from pathlib import Path

from models import ResultadoExtracao

ARQUIVO_SCHEMA = Path(__file__).resolve().parent.parent / "schema" / "resultado-extracao.schema.json"


def gerar_schema() -> dict:
    schema = ResultadoExtracao.model_json_schema(by_alias=True, mode="serialization")
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", **schema}


def schema_formatado() -> str:
    return json.dumps(gerar_schema(), indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    destino = Path(sys.argv[1]) if len(sys.argv) > 1 else ARQUIVO_SCHEMA
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(schema_formatado(), encoding="utf-8", newline="\n")
    print(f"schema gravado em {destino}")
