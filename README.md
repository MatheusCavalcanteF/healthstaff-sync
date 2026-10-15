# HealthStaff Sync

[![CI](https://github.com/MatheusCavalcanteF/healthstaff-sync/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MatheusCavalcanteF/healthstaff-sync/actions/workflows/ci.yml)

Conciliador de escalas de enfermagem e banco de horas, com auditoria automática de inconformidades trabalhistas.

O HealthStaff Sync lê as escalas mensais dos setores (PDF ou Excel) e o relatório de Banco de Horas do sistema de ponto **Two** (Excel ou CSV), cruza as duas fontes por colaborador e aponta, mês a mês, o que fere a legislação ou a política do hospital.

---

## Sumário

1. [Visão geral e proposta de valor](#1-visão-geral-e-proposta-de-valor)
2. [Arquitetura e stack](#2-arquitetura-e-stack)
3. [Regras de negócio do motor (`regras`)](#3-regras-de-negócio-do-motor-regras)
4. [Pré-requisitos e configuração do ambiente](#4-pré-requisitos-e-configuração-do-ambiente)
5. [Como executar os serviços](#5-como-executar-os-serviços)
6. [Endpoints da API REST](#6-endpoints-da-api-rest)
7. [Testes automatizados](#7-testes-automatizados)
8. [Roadmap](#8-roadmap)
9. [Dados sensíveis (LGPD)](#9-dados-sensíveis-lgpd)

---

## 1. Visão geral e proposta de valor

Em hospitais, a escala de enfermagem costuma viver em planilhas e PDFs por setor, enquanto o banco de horas fica no sistema de ponto. Ninguém enxerga as duas coisas juntas, e os problemas aparecem só na fiscalização ou na reclamação trabalhista.

O HealthStaff Sync resolve isso:

- **Reduz o risco de multas e passivos trabalhistas**, detectando antes do fechamento do mês as violações que geram autuação.
- **Detecta quebra de interjornada** (descanso menor que 11h entre jornadas, CLT art. 66), inclusive quando os plantões estão em setores diferentes, caso típico do folguista.
- **Detecta sobreposição de plantões e jornadas excessivas** (ex.: dobra PD + PN = 24h).
- **Controla a carga horária no regime 12x36** (180h/mês) e em jornadas semanais, comparando o escalado com a carga contratual e projetando o saldo do banco de horas.
- **Unifica o cadastro** a partir de fontes que não conversam entre si: a escala traz cargo, COREN e jornada; o Two traz nome e matrícula oficiais.

---

## 2. Arquitetura e stack

```text
┌────────────┐   REST/JSON    ┌──────────────────┐   multipart    ┌────────────────────┐
│  frontend  │ ─────────────▶ │   java-backend   │ ─────────────▶ │  python-extractor  │
│  Next.js   │ ◀───────────── │   Spring Boot    │ ◀───────────── │  FastAPI (interno) │
└────────────┘                └────────┬─────────┘ JSON (OpenAPI) └────────────────────┘
                                       │ JPA / Flyway
                                       ▼
                              ┌──────────────────┐
                              │  PostgreSQL 18   │
                              └──────────────────┘
```

**Fluxo:** Frontend → Java (`POST /api/v1/importacoes/*`) → Python (`POST /extracoes`) → JSON → Java persiste, valida e roda o motor de regras.

### Frontend (`frontend/`)

| Tecnologia | Uso |
| --- | --- |
| Next.js 16 (App Router) + React 19 | Páginas de dados como Server Components; interações (upload, filtros) no cliente |
| TypeScript | `src/types/api.ts` espelha os DTOs do Java |
| Tailwind CSS 4 | Estilo |
| Axios | Cliente HTTP (`src/lib/api/client.ts`); toda falha vira `ApiError` com o Problem Details do backend |
| Lucide Icons (`lucide-react`) | Ícones |
| date-fns | Datas, exibidas sempre no fuso `America/Sao_Paulo` |

Telas: Login, Usuários (só ADMIN), Dashboard do mês, Inconformidades (com filtros e painel de plantões), Colaboradores (busca e paginação server-side) e Importações (upload).

O token fica num cookie `httpOnly` lido só pelo servidor do Next: as páginas buscam dados em Server Components, e os uploads do navegador passam pelo repasse `/api/backend/*`, que acrescenta o token. O JavaScript da página nunca vê o JWT.

### Backend (`java-backend/`)

| Tecnologia | Uso |
| --- | --- |
| Java 25 + Spring Boot 4.1 | Orquestrador: recebe uploads, chama o extrator, persiste e aplica as regras |
| Spring Data JPA (Hibernate) | Persistência, com `ddl-auto: validate` (o Hibernate só confere o esquema) |
| Flyway | Migrações em `src/main/resources/db/migration` (dono do esquema) |
| PostgreSQL 18 | Banco de dados |
| RestClient (HTTP/1.1, Jackson 3) | `ExtratorClient`, cliente do extrator |
| openapi-generator | Gera as classes do contrato a partir de `python-extractor/schema/openapi.json` a cada build |
| Spring Security + JWT (OAuth2 Resource Server / Nimbus) | API sem estado, token HS256, perfis `ADMIN`, `GESTOR_RH` e `AUDITOR` |
| Spring Boot Actuator | Só `/actuator/health`: sem token, apenas o status geral; componentes (inclusive o `extrator`) só para `ADMIN`. O estado do extrator é reaproveitado por 10s, com uma consulta por vez e prazo de 3s |

Erros seguem Problem Details (RFC 9457), sempre com um campo `codigo`.

### Extrator de dados (`python-extractor/`)

Microsserviço interno e **stateless**: recebe arquivos, extrai, normaliza e devolve JSON (camelCase). Não persiste nada e não calcula regras trabalhistas.

| Tecnologia | Uso |
| --- | --- |
| FastAPI + Uvicorn | API HTTP (`/extracoes`, `/schema`, `/health`) |
| pdfplumber | Escalas em PDF |
| openpyxl | Escalas e relatórios do Two em Excel |
| rapidfuzz | Casamento aproximado de nomes (`ColaboradorMatcher`) |
| pydantic | Validação e geração do JSON Schema / OpenAPI do contrato |
| pandas | Só em desenvolvimento, para análise exploratória (não vai para a imagem Docker) |

Pipeline: Ingestão → Detecção de layout → Strategy de extração (`GridExtractor`, `PontoExcelExtractor`, `PontoCsvExtractor`) → Normalização de siglas → Match fuzzy de nomes → Validação.

**Contrato:** o esquema da resposta é versionado em `python-extractor/schema/` (`openapi.json` e `resultado-extracao.schema.json`). Mudou o modelo no Python → `python -m api.schema` → o build Java regenera as classes e reflete (ou quebra) na hora.

### Infraestrutura

| Tecnologia | Uso |
| --- | --- |
| Docker + Docker Compose | Sobe o extrator Python e o PostgreSQL 18 (`docker-compose.yml` na raiz) |

O extrator roda com sistema de arquivos somente leitura, uploads em `tmpfs` (nada de dado pessoal em disco), `cap_drop: ALL` e porta publicada só em `127.0.0.1`. O Java e o frontend rodam fora do compose.

---

## 3. Regras de negócio do motor (`regras`)

O motor roda automaticamente após cada importação nova (escala ou ponto) e sob demanda em `POST /api/v1/analises/{AAAA-MM}`. Falha na análise não desfaz a importação. As análises são versionadas por competência (uma vigente por mês).

A avaliação é **por pessoa, somando todos os setores**: o folguista que cobre UTI e Clínica Médica no mesmo mês é analisado como uma única agenda.

### Alertas gerados

| Tipo | Gravidade | Quando |
| --- | --- | --- |
| `SOBREPOSICAO_PLANTOES` | Crítica | Dois plantões do mesmo colaborador ao mesmo tempo, no mesmo setor ou em outro |
| `INTERJORNADA` | Alta | Descanso entre duas jornadas abaixo de 11h (CLT art. 66) |
| `JORNADA_EXCESSIVA` | Alta | Plantões emendados acima de 12h (ex.: PD + PN = 24h) |
| `FOLGA_BH_SEM_SALDO` | Alta | Folga de compensação (BH) sem saldo positivo para compensar |
| `SALDO_POSITIVO_ESCALA_ACIMA` | Média | Saldo alto (≥ 20h) e escala acima da carga: acumula ainda mais |
| `SALDO_NEGATIVO_ESCALA_ABAIXO` | Média | Saldo baixo (≤ −10h) e escala abaixo da carga: a dívida aumenta |

### Interjornada (11h)

- Exige descanso mínimo de 11h consecutivas entre jornadas, **inclusive entre setores diferentes**.
- Plantões separados por pausa de até 2h formam **uma única jornada** (ex.: M/T). Uma dobra PD + PN, portanto, gera alerta de jornada excessiva, não de interjornada.
- Cada alerta pertence ao mês do plantão que o causa: a virada do mês não duplica alertas.

### Consolidação de carga horária

Sem batidas de ponto, a consolidação compara a **carga cumprida** com a **carga contratual**:

- **Carga cumprida** = horas de plantão + crédito de Folga Organizacional (FG).
- **Carga contratual**: 12x36 = **180h/mês**; jornadas semanais "NNH" = NN × 5 (ex.: 40H = 200h).
- **Folga Organizacional (FG) é crédito de jornada, não redução de carga**: 12h por FG, até a cota mensal da categoria profissional (deduzida do cargo na escala):

  | Categoria | FGs creditáveis por mês |
  | --- | --- |
  | Enfermeiro(a) | 2 |
  | Técnico(a) de enfermagem | 1 |
  | Administrativo | 1 |
  | Cargo não reconhecido | 1 |

  FG além da cota não credita (esconderia débito de horas).
- A carga contratual é reduzida só por férias, licença ou afastamento, proporcionalmente aos dias.
- O resultado projeta o saldo do banco de horas do Two.

### Tolerância de afastamento

Com **28 dias ou mais** de férias/licença/afastamento no mês, a **carga esperada é zero**. A fração residual de 1 a 3 dias (em geral folgas regulares) geraria um débito artificial.

### Rastreamento de colaboradores: Oficial x Provisório

- O **Two é a fonte oficial** de nome e matrícula. Quem aparece nele tem cadastro **OFICIAL**.
- Quem está só na escala vira cadastro **PROVISÓRIO** (sem matrícula, único por nome normalizado) e é **promovido automaticamente** a oficial quando a matrícula aparece num relatório do Two.
- O extrator recebe o cadastro já conhecido pelo Java e faz o casamento aproximado de nomes (rapidfuzz). O matcher nunca descarta ninguém: quem não é encontrado é exportado com `matricula` nula e uma inconsistência.
- No Java, o `ColaboradorResolver` só usa igualdade exata de nome; homônimos nunca são adivinhados.
- A escala complementa cargo, COREN e jornada sem que uma fonte apague a outra.

Todos os parâmetros ficam em `healthstaff.regras.*` no `application.yml` e podem ser ajustados à política do hospital.

---

## 4. Pré-requisitos e configuração do ambiente

### Hardware e sistema operacional (Windows 11)

O Docker Desktop no Windows depende do WSL 2, que exige virtualização de hardware ativa.

1. **Ative a virtualização na BIOS/UEFI.** Em placas-mãe Gigabyte com processador AMD, a opção se chama **SVM Mode** (geralmente em *Tweaker* → *Advanced CPU Settings*). Salve e reinicie.
2. **Instale o WSL 2** num PowerShell como administrador:

   ```powershell
   wsl --install
   ```

   Reinicie o computador quando solicitado. Confira com `wsl --status` (versão padrão: 2).

### Softwares necessários

| Software | Versão | Para quê |
| --- | --- | --- |
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | Atual, com backend WSL 2 | Extrator Python e PostgreSQL |
| [Node.js](https://nodejs.org/) | 24 LTS | Frontend |
| Java JDK | 25 (com `JAVA_HOME` apontando para ele) | Backend |
| Git | Qualquer recente | Clonar o repositório |

Python 3 só é necessário para desenvolver o extrator fora do Docker (ver [Testes](#7-testes-automatizados)).

### Passo a passo

```powershell
# 1. Clonar
git clone git@github.com:MatheusCavalcanteF/healthstaff-sync.git
cd healthstaff-sync

# 2. Configurar o .env (único, na raiz: serve ao docker compose e ao Java)
copy .env.example .env
#    Edite o .env e defina POSTGRES_PASSWORD (obrigatória: o compose não sobe sem ela),
#    HEALTHSTAFF_JWT_SECRET (obrigatória para o backend) e o admin inicial
#    (HEALTHSTAFF_ADMIN_EMAIL / HEALTHSTAFF_ADMIN_SENHA).

# 3. Subir o extrator e o PostgreSQL
docker compose up --build -d
docker compose ps        # aguarde STATUS "healthy"
```

Variáveis principais do `.env`:

| Variável | Padrão | Descrição |
| --- | --- | --- |
| `POSTGRES_PASSWORD` | (vazia, obrigatória) | Senha do banco |
| `POSTGRES_USER` | `healthstaff` | Usuário do banco |
| `EXTRACTOR_API_KEY` | (vazia) | Chave exigida no header `X-API-Key` do extrator; a mesma chave configura o Java. Vazia = sem autenticação (só local) |
| `EXTRACTOR_MAX_UPLOAD_MB` | `20` | Limite de upload, aplicado igualmente no Java, no extrator e no repasse do Next |
| `EXTRACTOR_MAX_DESCOMPACTADO_MB` / `EXTRACTOR_MAX_ENTRADAS_ZIP` / `EXTRACTOR_MAX_PAGINAS_PDF` | `200` / `1000` / `200` | Limites de conteúdo por arquivo no extrator: `.xlsx` descompactado (zip bomb), entradas internas do `.xlsx` e páginas de PDF. Acima disso, 413 `ARQUIVO_MUITO_GRANDE` com o motivo |
| `EXTRACTOR_URL` | `http://localhost:8000` | Onde o Java encontra o extrator |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/healthstaff` | Onde o Java encontra o banco |
| `CORS_ORIGENS` | `http://localhost:3000` | Origens liberadas no backend (separadas por vírgula; nunca `*`) |
| `HEALTHSTAFF_JWT_SECRET` | (vazia, obrigatória) | Chave que assina os tokens, com pelo menos 32 caracteres (`openssl rand -base64 48`). O backend não sobe sem ela |
| `HEALTHSTAFF_JWT_EXPIRACAO` | `8h` | Validade do token; depois, novo login |
| `HEALTHSTAFF_ADMIN_EMAIL` / `HEALTHSTAFF_ADMIN_SENHA` | (vazias) | Primeiro administrador, criado na subida só se não houver nenhum ADMIN ativo (senha com 12+ caracteres) |

> Nenhum usuário vem pronto no banco (uma senha em migração ficaria no histórico do Git). Defina `HEALTHSTAFF_ADMIN_EMAIL` e `HEALTHSTAFF_ADMIN_SENHA` antes da primeira subida do backend e entre com eles em <http://localhost:3000/login>.

---

## 5. Como executar os serviços

Com o Docker já no ar (passo 3 acima), abra dois terminais:

### Backend (Java)

```powershell
cd java-backend
.\mvnw spring-boot:run
```

As migrações do Flyway são aplicadas na subida.

### Frontend (Next.js)

```powershell
cd frontend
npm install
copy .env.example .env.local   # NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
npm run dev
```

### URLs locais

| Serviço | URL |
| --- | --- |
| Frontend | <http://localhost:3000> |
| API Java | <http://localhost:8080> (`/api/v1/...`; saúde em `/actuator/health`, detalhada só com token de `ADMIN`) |
| Extrator Python | <http://localhost:8000> (documentação interativa em `/docs`) |

Healthcheck de container (Docker, Kubernetes): use `/actuator/health/liveness`, que não chama o extrator. Com o extrator fora do ar, `/actuator/health` responde 503 a qualquer um.

Para encerrar: `docker compose down` (com `-v` também apaga os dados do PostgreSQL).

---

## 6. Endpoints da API REST

### Java (exposto ao frontend), base `/api/v1`

Todas as rotas exigem `Authorization: Bearer <token>`, menos o login. Leitura (`GET`) vale para qualquer perfil; importações e reprocessamento são só de `ADMIN` e `GESTOR_RH` (`AUDITOR` é somente leitura).

| Método | Rota | Descrição |
| --- | --- | --- |
| `POST` | `/auth/login` | `{"email", "senha"}` → token JWT, validade (`expiraEm`) e dados do usuário. Após 5 tentativas sem acerto para o mesmo e-mail, 429 `MUITAS_TENTATIVAS` com `Retry-After` (bloqueio de 1 min que dobra a cada erro, até 15 min) |
| `GET` | `/auth/eu` | Usuário do token |
| `GET` | `/usuarios` | Usuários (só `ADMIN`), ativos e inativos |
| `POST` | `/usuarios` | Cadastra (`nome`, `email`, `senha`, `perfil`); 409 `EMAIL_JA_CADASTRADO` se o e-mail existir |
| `PUT` | `/usuarios/{id}` | Atualiza nome, e-mail e perfil; `senha` em branco mantém a atual |
| `PATCH` | `/usuarios/{id}/status` | `{"ativo": true/false}`; nunca deixa o sistema sem um `ADMIN` ativo (409 `ULTIMO_ADMIN`) |
| `POST` | `/importacoes/escalas` | Upload da escala (multipart, campo `arquivo`). Envia ao extrator também o cadastro já conhecido, para o casamento de nomes |
| `POST` | `/importacoes/ponto` | Upload do Banco de Horas do Two (multipart, campo `arquivo`; `dataReferencia` opcional, `AAAA-MM-DD`) |
| `GET` | `/importacoes/{id}` | Detalhe de uma importação |
| `POST` | `/analises/{AAAA-MM}` | Roda o motor de regras sob demanda (422 `SEM_ESCALA_VIGENTE` se o mês não tem escala) |
| `GET` | `/analises/{AAAA-MM}` | Resumo do mês (dashboard): contagem por gravidade e tipo, colaboradores afetados, horas agregadas e exposição do banco de horas |
| `GET` | `/analises/{AAAA-MM}/inconformidades` | Alertas, mais graves primeiro, com colaborador e plantões/setores envolvidos. Filtros: `tipo`, `gravidade`, `colaboradorId` |
| `GET` | `/colaboradores` | Lista paginada (`pagina` desde 0, `tamanho` 1-200). Filtros: `vinculo` (`OFICIAL`/`PROVISORIO`) e `busca` (nome sem acento ou início da matrícula). Traz categoria e saldo mais recente do Two |

**Respostas de importação:** `201` + `Location` (importação nova) ou `200` com `duplicada: true` (mesmo arquivo, inclusive em envio simultâneo).

**Erros** (Problem Details, RFC 9457, sempre com `codigo`): `ARQUIVO_AUSENTE`, `ARQUIVO_INVALIDO`, `ARQUIVO_MUITO_GRANDE` (413), `PARAMETRO_INVALIDO`, `IMPORTACAO_NAO_ENCONTRADA`, `ANALISE_NAO_ENCONTRADA`, `SEM_ESCALA_VIGENTE` (422), `NAO_AUTENTICADO` (401), `ACESSO_NEGADO` (403), `CREDENCIAIS_INVALIDAS` (401, login), `MUITAS_TENTATIVAS` (429 com `Retry-After`, login), `EMAIL_JA_CADASTRADO`, `ULTIMO_ADMIN` e `ALTERACAO_PROPRIA_BLOQUEADA` (409), `USUARIO_NAO_ENCONTRADO` (404), `EXTRATOR_INDISPONIVEL` (503) ou o código repassado pelo extrator (`TIPO_INCOMPATIVEL`, `FORMATO_NAO_RECONHECIDO`...).

### Python (interno, só o Java chama)

| Método | Rota | Descrição |
| --- | --- | --- |
| `POST` | `/extracoes` | Multipart com `escala` e/ou `banco_horas`; `data_referencia_ponto` e `cadastro` (JSON `[{"matricula","nome"}]`) opcionais |
| `GET` | `/schema` | JSON Schema da resposta |
| `GET` | `/health` | Saúde do serviço |

Erros no formato `ErroApi` (`codigo`, `mensagem`, `campo`, `arquivo`). Falha em um arquivo derruba a requisição inteira (sem lote parcial).

---

## 7. Testes automatizados

Resultado da última execução completa (08/10/2026, com segurança e gestão de usuários), sem nenhuma falha:

| Módulo | Comando | Resultado |
| --- | --- | --- |
| python-extractor | `venv\Scripts\python -m pytest` | **367 aprovados** |
| java-backend | `.\mvnw verify` | **289 testes: 286 aprovados, 3 pulados, 0 falhas** |

Os 3 testes pulados são o `ExtratorE2ETest`, que roda contra o extrator Python real e só é habilitado com `EXTRACTOR_E2E_URL` (e `EXTRACTOR_E2E_API_KEY`) definidas.

### Integração contínua

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) roda a cada push na `main` e em todo pull request para a `main`, com três jobs em paralelo no `ubuntu-latest`:

| Job | Ambiente | Passos |
| --- | --- | --- |
| `java-backend` | JDK 25 (Temurin), cache do Maven | `./mvnw verify -B` (Postgres embutido, sem Docker); relatórios do Surefire como artefato se falhar |
| `python-extractor` | Python 3.13 (o mesmo da imagem Docker), cache do pip | `pytest` e checagem de que `schema/` está em dia com os modelos (`python -m api.schema` sem diff) |
| `next-frontend` | Node.js 24 LTS, cache do npm | `npm ci`, `npm run lint`, `npm test` e `npm run build` (inclui a checagem de tipos) |

A checagem do schema existe porque o backend gera o cliente do extrator a partir do `openapi.json` versionado: um modelo Python alterado sem regenerar o schema faria o Java compilar contra um contrato velho.

### Python

```powershell
cd python-extractor
python -m venv venv
venv\Scripts\pip install -r requirements-dev.txt
venv\Scripts\python -m pytest
```

`tests/test_arquivos_reais.py` valida só invariantes e é pulado quando os arquivos reais não estão presentes.

### Java

```powershell
cd java-backend
.\mvnw verify
```

Os testes usam PostgreSQL 18 **embutido** (io.zonky), sem Docker. O `ExtratorClientTest` valida o cliente contra uma resposta-exemplo do contrato gerada pela própria API Python com dados fictícios.

### Frontend

```powershell
cd frontend
npm run lint
npm test        # unitários com o node --test nativo (src/**/*.test.ts)
npm run build   # inclui a checagem de tipos
```

---

## 8. Roadmap

- [x] **Autenticação JWT com Spring Security**, com login no frontend (cookie `httpOnly`).
- [x] **Gestão de usuários pela interface** (só `ADMIN`): cadastro, edição, ativação/desativação, com revogação imediata de sessões.
- [ ] **Controle de acesso por perfis (RBAC) completo**: hoje há a regra mínima (`AUDITOR` só lê; `ADMIN` e `GESTOR_RH` importam e reprocessam; só `ADMIN` gere usuários). Faltam escopo por setor e trilha de auditoria (quem alterou o quê).
- [ ] **Troca de senha pelo próprio usuário** (hoje só o `ADMIN` define senhas).
- [ ] **Proteção contra força bruta no login** (limite de tentativas por e-mail/IP).
- [ ] **Deploy na nuvem** dos três serviços e do banco gerenciado.

---

## 9. Dados sensíveis (LGPD)

Escalas, relatórios do Two e espelhos de ponto contêm nomes, matrículas, COREN e CPF.

- Nunca versione arquivos reais (já bloqueados no `.gitignore`).
- Nunca copie valores reais para código ou testes: use dados fictícios.
- O extrator não grava os arquivos recebidos; no container, uploads vão para memória (`tmpfs`) e somem com ele.
