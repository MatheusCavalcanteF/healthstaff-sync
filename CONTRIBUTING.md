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
- **Endpoints do Java (expostos ao frontend):** multipart com o campo `arquivo`.
  - `POST /api/v1/importacoes/escalas`: envia ao extrator a escala **e o cadastro já conhecido** (matrícula + nome de todos no banco), para o casamento aproximado de nomes funcionar com a escala sozinha.
  - `POST /api/v1/importacoes/ponto` (+ `dataReferencia` opcional, AAAA-MM-DD).
  - `GET /api/v1/importacoes/{id}`.
  - 201 + `Location` (novo) | 200 com `duplicada: true` (mesmo arquivo, inclusive envio simultâneo). Erros em Problem Details (RFC 9457) sempre com `codigo`: `ARQUIVO_AUSENTE`, `ARQUIVO_INVALIDO`, `ARQUIVO_MUITO_GRANDE` (413), `PARAMETRO_INVALIDO`, `IMPORTACAO_NAO_ENCONTRADA`, `EXTRATOR_INDISPONIVEL` (503, sem detalhe interno) ou o código do extrator (`TIPO_INCOMPATIVEL`, `FORMATO_NAO_RECONHECIDO`...).
  - A chamada ao extrator fica fora da transação; a gravação é uma transação única.
- **Endpoints do Python (internos, só o Java chama):**
  - `POST /extracoes`: multipart com `escala` e/ou `banco_horas`, `data_referencia_ponto` e `cadastro` (JSON `[{"matricula","nome"}]`) opcionais. O matcher usa o `cadastro` somado ao Banco de Horas enviado (este prevalece).
  - `GET /schema`: JSON Schema da resposta. Versionado em `python-extractor/schema/resultado-extracao.schema.json` (regenerar com `python -m api.schema`).
  - `GET /health`.
- **Erros:** formato único `ErroApi` (`codigo`, `mensagem`, `campo`, `arquivo`); falha em um arquivo derruba a requisição inteira (sem lote parcial).
- **Configuração do extrator:** `EXTRACTOR_API_KEY` (exige `X-API-Key`), `EXTRACTOR_MAX_UPLOAD_MB` (padrão 20).

## Desenvolvimento (python-extractor)
- Dependências: `requirements.txt` = produção (vai para a imagem Docker); `requirements-dev.txt` = produção + pytest/httpx2/pandas. Instalar com `pip install -r requirements-dev.txt`.
- Testes: `venv\Scripts\python -m pytest` (a partir de `python-extractor/`).
- API local: `uvicorn main:app --reload` -> docs em `http://127.0.0.1:8000/docs`.
- Docker (na raiz): `docker compose up --build -d`; o extrator fica em `http://127.0.0.1:8000`. O `.dockerignore` é lista de permissão: arquivo novo que precise ir para a imagem tem de ser liberado explicitamente nele e copiado no `Dockerfile`.

## Desenvolvimento (java-backend)
- Spring Boot 4.1 / Java 25 / Maven Wrapper: `./mvnw verify` (a partir de `java-backend/`). Requer `JAVA_HOME` apontando para um JDK 25.
- **Classes do contrato são geradas, não escritas:** o `openapi-generator` lê `python-extractor/schema/openapi.json` e gera `br.com.healthstaff.backend.extrator.contrato.*` em `target/` a cada build. Mudou o contrato no Python -> `python -m api.schema` -> o build Java reflete (ou quebra) na hora. `jsonschema2pojo` foi descartado: não entende `$defs`/`anyOf` do Draft 2020-12.
- Geração: `DateTime` -> `LocalDateTime` (o Python envia hora local sem fuso); enums desconhecidos viram `unknown_default_open_api` (códigos novos de inconsistência não quebram), mas `versaoContrato` diferente de 1.0 é rejeitada pelo cliente.
- Cliente: `ExtratorClient` (RestClient, HTTP/1.1, Jackson 3). Arquivo recusado -> `ExtracaoRejeitadaException` (erro do usuário, carrega o `ErroApi`); serviço fora/timeout/401/5xx/resposta fora do contrato -> `ExtratorIndisponivelException`. Saúde do extrator em `/actuator/health` (componente `extrator`).
- Testes: `ExtratorClientTest` usa um servidor HTTP falso e a resposta-exemplo `src/test/resources/contrato/resultado-extracao.exemplo.json` (gerada pela API Python com dados fictícios e validada por um teste Python). `ExtratorE2ETest` roda contra o Python real só com `EXTRACTOR_E2E_URL` (e `EXTRACTOR_E2E_API_KEY`) definidas.
- Persistência: Postgres 18 + Flyway (`src/main/resources/db/migration`) + JPA com `ddl-auto: validate` (o esquema é só do Flyway; o Hibernate apenas confere). Testes usam Postgres 18 **embutido** (io.zonky, sem Docker) via `IntegracaoComBanco`; toda classe `@SpringBootTest` deve estendê-la.
- Modelo: `importacao` (um lote do extrator; `(tipo, hash_sha256)` único = mesmo arquivo não reimporta) -> `inconsistencia`; `escala` versionada por setor + competência (só uma `vigente`, garantido por índice parcial; versões antigas ficam como histórico) -> `plantao` (com `setor_id` próprio); `saldo_banco_horas` (um retrato por relatório do Two).
- Colaborador sem matrícula = cadastro provisório (único por nome normalizado), promovido quando a matrícula aparece no Two. `ColaboradorResolver` só usa igualdade exata de nome; homônimos nunca são adivinhados (o fuzzy é no extrator).
- O Two é a fonte oficial de nome/matrícula; a escala complementa cargo, COREN e jornada sem que uma fonte apague a outra.
- Upload: limite do Java = `EXTRACTOR_MAX_UPLOAD_MB` (o mesmo do extrator). `server.tomcat.max-swallow-size` é alto de propósito: sem isso, uploads bem acima do limite derrubam a conexão em vez de receber o 413.
- **Motor de regras** (`regras`): roda automaticamente após cada importação nova (escala ou ponto) e sob demanda em `POST /api/v1/analises/{AAAA-MM}` (consulta: ver API de leitura abaixo). Falha na análise não desfaz a importação. Mês sem escala vigente não gera análise (422 `SEM_ESCALA_VIGENTE` no POST).
  - Regras puras e testáveis sem Spring: `RegraJornadas` (interjornada 11h, sobreposição, jornada excessiva) e `ConsolidacaoCalculadora` (escalado x carga x saldo do Two). Parâmetros em `healthstaff.regras.*`.
  - Avaliação por pessoa, somando todos os setores (folguista); plantões com pausa de até 2h = uma jornada (M/T, dobra PD+PN -> alerta de jornada excessiva, não de interjornada). Cada alerta pertence ao mês do plantão que o causa (virada do mês sem duplicar).
  - Sem batidas de ponto, a "consolidação" compara a **carga cumprida = horas de plantão + crédito de FG** com a carga contratual (12x36 = 180h; "NNH" = NN x 5) reduzida só por férias/licença/afastamento (proporcional aos dias; com 28 dias ou mais de ausência no mês, `dias-ausencia-para-zerar-carga`, a carga esperada é zero), e projeta o saldo do Two.
  - **Folga organizacional (FG) é crédito de jornada, não redução de carga:** 12h por FG, até a cota mensal da categoria (enfermeiro(a) 2; técnico(a) de enfermagem e administrativo 1; cargo não reconhecido 1). FG além da cota não credita. Categoria deduzida do cargo da escala (`CategoriaProfissional`).
  - Análises versionadas por competência (uma vigente); `inconformidade` (regra de negócio) é separada de `inconsistencia` (extração).
- **API de leitura (frontend):**
  - `GET /api/v1/analises/{AAAA-MM}`: resumo do mês (contagem por gravidade e tipo, sempre com todas as chaves; colaboradores afetados; horas agregadas no banco e exposição do banco de horas).
  - `GET /api/v1/analises/{AAAA-MM}/inconformidades`: alertas, mais graves primeiro, com colaborador e plantões/setores envolvidos; filtros `tipo`, `gravidade`, `colaboradorId`.
  - `GET /api/v1/colaboradores`: paginado (`pagina` desde 0, `tamanho` 1-200), filtros `vinculo` (OFICIAL/PROVISORIO) e `busca` (nome sem acento ou início da matrícula); traz categoria (nula se o cargo é desconhecido) e saldo mais recente do Two. Formato de página: `web.Pagina`.
- Configuração local: um único `.env` na raiz (copiar de `.env.example`) serve ao docker compose e ao Java (`spring.config.import`). `POSTGRES_PASSWORD` é obrigatória.
- Variáveis de ambiente compartilhadas entre os serviços usam sempre o prefixo `EXTRACTOR_` (`EXTRACTOR_URL`, `EXTRACTOR_API_KEY`): a mesma chave configura o Java e o Python.

## Dados sensíveis (LGPD)
- Escalas, relatórios do Two e espelhos de ponto contêm nomes, matrículas, COREN e CPF. Nunca versionar arquivos reais (bloqueados no `.gitignore`) nem copiar valores reais para código ou testes; usar dados fictícios.
- `tests/test_arquivos_reais.py` valida só invariantes e é pulado quando os arquivos reais não estão presentes.
