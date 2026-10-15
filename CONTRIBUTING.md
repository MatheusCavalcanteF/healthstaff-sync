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
- **Endpoints do Java (expostos ao frontend):** todos exigem `Authorization: Bearer <jwt>` (ver Segurança), menos o login. Importações: multipart com o campo `arquivo`.
  - `POST /api/v1/auth/login` (`{"email","senha"}` -> `TokenResponse`) e `GET /api/v1/auth/eu`.
  - `/api/v1/usuarios` (só ADMIN): `GET` (todos, por nome), `GET /{id}`, `POST` (201 + `Location`), `PUT /{id}` (senha em branco mantém a atual), `PATCH /{id}/status` (`{"ativo": bool}`). Sem DELETE: desativar preserva o histórico.
  - `POST /api/v1/importacoes/escalas`: envia ao extrator a escala **e o cadastro já conhecido** (matrícula + nome de todos no banco), para o casamento aproximado de nomes funcionar com a escala sozinha.
  - `POST /api/v1/importacoes/ponto` (+ `dataReferencia` opcional, AAAA-MM-DD).
  - `GET /api/v1/importacoes/{id}`.
  - 201 + `Location` (novo) | 200 com `duplicada: true` (mesmo arquivo, inclusive envio simultâneo). Erros em Problem Details (RFC 9457) sempre com `codigo`: `ARQUIVO_AUSENTE`, `ARQUIVO_INVALIDO`, `ARQUIVO_MUITO_GRANDE` (413), `PARAMETRO_INVALIDO`, `IMPORTACAO_NAO_ENCONTRADA`, `NAO_AUTENTICADO` (401), `ACESSO_NEGADO` (403), `CREDENCIAIS_INVALIDAS` (401, login), `MUITAS_TENTATIVAS` (429 + `Retry-After`, login), `EMAIL_JA_CADASTRADO`/`ULTIMO_ADMIN`/`ALTERACAO_PROPRIA_BLOQUEADA` (409), `USUARIO_NAO_ENCONTRADO`, `EXTRATOR_INDISPONIVEL` (503, sem detalhe interno) ou o código do extrator (`TIPO_INCOMPATIVEL`, `FORMATO_NAO_RECONHECIDO`...).
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
- Cliente: `ExtratorClient` (RestClient, HTTP/1.1, Jackson 3). Arquivo recusado -> `ExtracaoRejeitadaException` (erro do usuário, carrega o `ErroApi`); serviço fora/timeout/401/5xx/resposta fora do contrato -> `ExtratorIndisponivelException`. Saúde do extrator em `/actuator/health` (componente `extrator`, visível só para ADMIN; falha vira detalhe genérico e a mensagem completa, com a URL interna, vai para o log). O `GET /health` usa um `RestClient` próprio (`extratorSaudeRestClient`) com `timeout-saude` (3s, conexão e resposta), separado dos 120s da extração.
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
- **CORS:** `web.CorsConfig` libera `/api/**` para as origens de `healthstaff.cors.origens-permitidas` (padrão `http://localhost:3000`; variável `CORS_ORIGENS`), todos os métodos/headers, expondo `Location`. Nunca usar `*`. É um `CorsConfigurationSource` aplicado pelo filtro do Spring Security (`http.cors()`), antes da autenticação: preflight sem token e 401/403 com cabeçalhos CORS.
- **Segurança** (`seguranca`): Spring Security sem estado + JWT HS256 nativo (`spring-boot-starter-security-oauth2-resource-server`, Nimbus). O `jjwt` foi descartado: o `jjwt-jackson` puxaria o Jackson 2 ao lado do Jackson 3.
  - Livres: `POST /api/v1/auth/login` e `GET /actuator/health` (único endpoint do Actuator exposto; `info` saiu). Anônimo e perfis não-ADMIN veem só `status` (+ os nomes dos grupos de probes, padrão do Boot 4); componentes e detalhes só com `management.endpoint.health.roles: ADMIN`. Sem `management.endpoint.health.cache` (é por principal e ignorado com query string): o `ExtratorHealthIndicator` guarda o último resultado por 10s para qualquer requisição e faz uma consulta ao extrator por vez; vencido o resultado, as requisições simultâneas recebem o anterior (só na primeira consulta, sem resultado anterior, elas esperam a que está em andamento). Testes: `ExtratorHealthIndicatorTest` (relógio controlável, `RelogioDeTeste`) e `HealthcheckTest`. Healthcheck de container (Docker, Kubernetes) deve usar `/actuator/health/liveness`, que não chama o extrator (com ele fora, `/actuator/health` dá 503 a qualquer um). `/api/v1/usuarios/**` (qualquer método): só `ADMIN`, regra no `SecurityConfig` (sem `@PreAuthorize`: um ponto só para acesso por rota). `GET /api/v1/**`: qualquer perfil. Demais métodos em `/api/v1/**`: só `ADMIN` e `GESTOR_RH` (`AUDITOR` é só leitura). Qualquer outra rota: negada.
  - `UsuarioDoToken` recarrega o usuário do banco a cada requisição e compara a claim `ver` do token com `usuario.versao_credenciais`: trocar senha, e-mail ou perfil, ou desativar, incrementa a versão e derruba as sessões abertas na hora (reativar não ressuscita tokens antigos).
  - `UsuarioService`: e-mail único (409, também pelo unique do banco em corrida); sempre resta um ADMIN ativo (desativar ou rebaixar; os admins ativos ficam travados com `select ... for update`, então dois admins se desativando ao mesmo tempo não zeram o sistema); ninguém desativa a si mesmo nem tira o próprio perfil de ADMIN. Pela API, `ULTIMO_ADMIN` só é alcançável em corrida (quem chama é sempre outro admin ativo); `UsuarioServiceTest` cobre isso em paralelo.
  - Senha: `PoliticaSenha` (12 caracteres a 72 bytes, limite do BCrypt), a mesma para o admin inicial e para a API.
  - Força bruta: `LimiteTentativasLogin` conta cada tentativa por e-mail **antes** de conferir a senha (paralelas não passam juntas); depois de `healthstaff.security.login.tentativas-livres` (5) sem acerto, 429 `MUITAS_TENTATIVAS` com `Retry-After`, bloqueio de 1 min que dobra a cada nova tentativa errada até 15 min; acerto zera; igual para e-mail sem conta. Em memória (cada instância conta à parte). O backend só vê o IP do servidor Next, por isso o limite por IP fica na Server Action do login.
  - 401/403 saem pelo `ErrosApiHandler` (os filtros delegam ao `handlerExceptionResolver`). Login recusado tem sempre a mesma resposta (e-mail inexistente, senha errada, inativo) e passa pelo BCrypt também para inativos (sem vazar a conta pelo tempo).
  - `HEALTHSTAFF_JWT_SECRET` é obrigatória (>= 32 bytes, sem padrão: o backend não sobe sem ela). Validade em `HEALTHSTAFF_JWT_EXPIRACAO` (padrão 8h, sem refresh).
  - **Nenhum usuário em migração** (senha no histórico do Git valeria em todo ambiente): `CriacaoAdminInicial` cria o primeiro ADMIN na subida a partir de `HEALTHSTAFF_ADMIN_EMAIL`/`HEALTHSTAFF_ADMIN_SENHA` (>= 12 caracteres), só se não houver ADMIN ativo.
  - Testes: os MockMvc de API usam `@WithMockUser(roles = "GESTOR_RH")`; as regras de acesso são testadas pelos filtros reais em `SegurancaTest` e, no Actuator, em `HealthcheckTest` (usuários via `UsuariosDeTeste`).
- Configuração local: um único `.env` na raiz (copiar de `.env.example`) serve ao docker compose e ao Java (`spring.config.import`). `POSTGRES_PASSWORD` é obrigatória.
- Variáveis de ambiente compartilhadas entre os serviços usam sempre o prefixo `EXTRACTOR_` (`EXTRACTOR_URL`, `EXTRACTOR_API_KEY`): a mesma chave configura o Java e o Python.

## Desenvolvimento (frontend)
- Next.js 16 (App Router) + React 19 + Tailwind 4 + TypeScript, em `frontend/`. **Next 16 tem mudanças incompatíveis**: ler `frontend/AGENTS.md` e a documentação em `frontend/node_modules/next/dist/docs/` antes de mexer em rotas (`params`/`searchParams` são Promises; `export const dynamic` não existe mais; renderização por requisição via `await connection()`).
- Requer Node.js 24 LTS. `npm run lint`, `npm test` e `npm run build` (faz a checagem de tipos).
- Testes unitários: `node --test` nativo (sem dependência), em `src/**/*.test.ts`. O módulo testado não pode usar aliases (`@/`) nem sintaxe TypeScript não apagável (enum, parameter properties); o teste importa com a extensão `.ts` (`allowImportingTsExtensions`).
- `src/types/api.ts` espelha os DTOs Java (atualizar junto quando um record de resposta mudar). `src/lib/api/client.ts`: toda falha vira `ApiError` com o `ProblemDetail` (`erro.codigo`; `SEM_CONEXAO` quando o backend não responde).
- **Sessão:** o login é uma Server Action (`app/login/actions.ts`) que grava o JWT num cookie `httpOnly` + `SameSite=Lax` (`lib/sessao.ts`, só no servidor). Nome e atributos vêm de `lib/cookie-sessao.ts`: em produção `__Host-hs_sessao` (Secure, Path=/, sem Domain: nem subdomínio nem página HTTP o gravam ou sobrescrevem); em dev `hs_sessao` sem Secure. Apagar usa os mesmos atributos (o navegador recusa remover um `__Host-` sem Secure e Path=/). Trocar o nome derruba as sessões abertas uma vez (novo login). O navegador nunca vê o token nem fala direto com o backend:
  - Server Components chamam via `lib/api/endpoints.ts` (`server-only`; `apiServidor` acrescenta o Bearer; 401 `NAO_AUTENTICADO` -> `/login?motivo=sessao-expirada`).
  - O navegador (uploads, gestão de usuários) chama `lib/api/navegador.ts` -> `app/api/backend/[...caminho]` (repasse que lê o cookie; recusa `..` e `Sec-Fetch-Site: cross-site`).
  - Login: além do limite por e-mail do backend, a Server Action limita 20 tentativas sem acerto por IP a cada 15 min (`lib/limite-login.ts`; login aceito não conta). O IP vem do `X-Real-IP` ou do último `X-Forwarded-For`: só é confiável atrás de um proxy reverso.
  - `src/proxy.ts` (antigo middleware) só confere se o cookie existe; `/api/` fica fora do matcher (o proxy bufferizaria uploads acima de 10 MB). Nome e perfil exibidos vêm do payload do JWT sem verificar a assinatura: só interface; quem barra é o backend.
- **Cabeçalhos de segurança** em `next.config.ts` (todas as rotas): CSP sem nonce, na variante "Without Nonces" da documentação do Next 16, que mantém as páginas estáticas ao custo de `'unsafe-inline'` em `script-src`; inclui `frame-ancestors 'none'`, `object-src 'none'`, `base-uri`/`form-action 'self'` e `connect-src 'self'` (o navegador só fala com a própria origem). `'unsafe-eval'` só em dev (React). Também `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` e, só em produção, HSTS e `upgrade-insecure-requests`; sem `X-Powered-By`. Recurso de outra origem (CDN, fonte externa) exige ajustar a CSP. Teste em `src/seguranca/cabecalhos.test.ts`.
- Páginas de dados buscam no servidor (Server Components); depois de uma alteração no navegador, `router.refresh()` recarrega os dados. Erros de API viram `BannerErro` (`components/estado/Banner.tsx`, título por `codigo`). Datas exibidas no fuso `America/Sao_Paulo`, independente do fuso do servidor.
- Erros esperados viram estados de tela: `ANALISE_NAO_ENCONTRADA` -> estado vazio com atalho para Importações; demais -> mensagem de erro (nunca confundir "sem dados" com "servidor fora").

## CI (GitHub Actions)
- `.github/workflows/ci.yml`: push na `main` e PR para a `main`, três jobs paralelos (`java-backend` com `./mvnw verify -B`; `python-extractor` com `pytest` + `schema/` sem diff após `python -m api.schema`; `next-frontend` com `npm ci`, lint, `npm test` e build). Versões iguais às de produção: Java 25, Python 3.13 (Dockerfile), Node 24.
- Mudou um modelo do extrator: regenerar e versionar o schema, senão o CI falha (o Java gera o cliente a partir dele).

## Dados sensíveis (LGPD)
- Escalas, relatórios do Two e espelhos de ponto contêm nomes, matrículas, COREN e CPF. Nunca versionar arquivos reais (bloqueados no `.gitignore`) nem copiar valores reais para código ou testes; usar dados fictícios.
- `tests/test_arquivos_reais.py` valida só invariantes e é pulado quando os arquivos reais não estão presentes.
