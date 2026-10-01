# HealthStaff Sync - Conciliador de Escalas e Banco de Horas

## Arquitetura e Decisões de Design

### 1. Módulo Python (python-extractor)
- **Função:** Microsserviço interno e stateless de extração. Recebe arquivos, extrai, normaliza e devolve JSON. Não persiste arquivos nem dados e não chama o Java.
- **Pipeline:** Ingestão -> Detecção de Layout -> Padrão Strategy de Extração -> Normalização de Siglas -> Match Fuzzy de Nomes (`rapidfuzz`) -> Validação (`pydantic`).
- **Formatos:** escala em grade mensal (PDF ou Excel) via `GridExtractor`; Banco de Horas do Two em Excel ou CSV via `PontoExcelExtractor`/`PontoCsvExtractor` (lógica comum em `ponto_base`). Ambos os formatos de ponto devem continuar suportados.
- **Matcher:** o `ColaboradorMatcher` infere a matrícula pelo nome, mas nunca é barreira: quem está na escala e não está no Two é exportado com `matricula` nula e uma inconsistência.
- **Contrato:** Devolve fatos brutos/normalizados em JSON (camelCase). Não calcula regras trabalhistas. Problemas recuperáveis viram `inconsistencias` dentro do lote.

### 2. Módulo Java (java-backend)
- **Função:** Orquestrador principal em Spring Boot: recebe uploads do frontend, autentica, chama o extrator Python, persiste e aplica o motor de regras de negócio (alertas/sugestões).
- **Modelagem do Folguista:** No banco, o setor pertence ao `Plantao` e não ao `Colaborador`. O JSON do extrator é hierárquico (`Setor -> Colaborador -> Plantao`, com o folguista repetido em cada setor que cobre); cabe ao Java fazer esse mapeamento relacional na persistência.
- **Regras:** Validação de Interjornada (11h), Sobreposição de Plantões, Habilitação no Setor e Saldo de Banco de Horas.

### 3. Integração
- **Fluxo:** Frontend -> Java (`POST /api/v1/importacoes/*`) -> Python (`POST /extracoes`) -> JSON -> Java persiste e valida.
- **Endpoints do Java (expostos ao frontend):**
  - `POST /api/v1/importacoes/escalas`
  - `POST /api/v1/importacoes/ponto`
- **Endpoints do Python (internos, só o Java chama):**
  - `POST /extracoes`: multipart com `escala` e/ou `banco_horas` e `data_referencia_ponto` opcional. Processa os dois na mesma requisição: o Banco de Horas é extraído primeiro e serve de cadastro para o matcher resolver as matrículas da escala.
  - `GET /schema`: JSON Schema da resposta. Versionado em `python-extractor/schema/resultado-extracao.schema.json` (regenerar com `python -m api.schema`).
  - `GET /health`.
- **Erros:** formato único `ErroApi` (`codigo`, `mensagem`, `campo`, `arquivo`); falha em um arquivo derruba a requisição inteira (sem lote parcial).
- **Configuração do extrator:** `EXTRACTOR_API_KEY` (exige `X-API-Key`), `EXTRACTOR_MAX_UPLOAD_MB` (padrão 20).

## Desenvolvimento (python-extractor)
- Dependências: `requirements.txt` = produção (vai para a imagem Docker); `requirements-dev.txt` = produção + pytest/httpx2/pandas. Instalar com `pip install -r requirements-dev.txt`.
- Testes: `venv\Scripts\python -m pytest` (a partir de `python-extractor/`).
- API local: `uvicorn main:app --reload` -> docs em `http://127.0.0.1:8000/docs`.
- Docker (na raiz): `docker compose up --build -d`; o extrator fica em `http://127.0.0.1:8000`. O `.dockerignore` é lista de permissão: arquivo novo que precise ir para a imagem tem de ser liberado explicitamente nele e copiado no `Dockerfile`.

## Dados sensíveis (LGPD)
- Escalas, relatórios do Two e espelhos de ponto contêm nomes, matrículas, COREN e CPF. Nunca versionar arquivos reais (bloqueados no `.gitignore`) nem copiar valores reais para código ou testes; usar dados fictícios.
- `tests/test_arquivos_reais.py` valida só invariantes e é pulado quando os arquivos reais não estão presentes.
