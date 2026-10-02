# HealthStaff Sync — Frontend

Next.js 16 (App Router) + React 19 + Tailwind CSS 4 + TypeScript. Consome a API
do `java-backend` (`/api/v1`).

> Next 16 tem mudanças incompatíveis com versões anteriores (ex.: `params` e
> `searchParams` são Promises). Veja `AGENTS.md` e a documentação em
> `node_modules/next/dist/docs/` antes de alterar rotas.

## Rodando

Requer Node.js 24 LTS.

```bash
npm install
cp .env.example .env.local   # ajuste NEXT_PUBLIC_API_URL se o backend não estiver em localhost:8080
npm run dev                  # http://localhost:3000
```

O backend Java precisa estar no ar (`./mvnw spring-boot:run` em `java-backend/`).

## Estrutura

| Rota | Tela |
| --- | --- |
| `/importacoes` | Envio da escala e do relatório de Banco de Horas do Two |
| `/analises/{AAAA-MM}` | Dashboard do mês |
| `/analises/{AAAA-MM}/inconformidades` | Alertas do mês |
| `/colaboradores` | Cadastro oficial e provisório |

`/` e `/analises` redirecionam para o mês corrente (calculado a cada requisição).

- `src/types/api.ts` — tipos espelhando os DTOs do backend.
- `src/lib/api/client.ts` — instância Axios; toda falha vira `ApiError` com o `ProblemDetail` do backend (`erro.codigo`).
- `src/lib/api/endpoints.ts` — chamadas tipadas.

## Verificação

```bash
npm run lint
npm run build
```
