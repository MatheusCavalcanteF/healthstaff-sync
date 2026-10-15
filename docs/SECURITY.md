# Política de segurança

## Como reportar uma vulnerabilidade

Use o **reporte privado de vulnerabilidades** do GitHub: aba *Security* → *Report a vulnerability* ([link direto](https://github.com/MatheusCavalcanteF/healthstaff-sync/security/advisories/new)). O relato fica visível só para quem mantém o projeto até a correção.

Não abra issue pública nem PR para falhas de segurança. Inclua, se possível: o componente afetado, os passos para reproduzir e o impacto esperado.

## Escopo

- Código deste repositório: `java-backend` (API Spring Boot), `python-extractor` (FastAPI), `frontend` (Next.js), o `Dockerfile`, o `docker-compose.yml` e os workflows em `.github/`.
- Dependências diretas, quando a falha for explorável na forma como o projeto as usa.

Fora do escopo: ambientes de terceiros que rodem o projeto, falhas que exijam acesso prévio à máquina ou ao `.env`, e alertas de dependência que já aparecem no Dependabot.

O projeto trata dados pessoais (escalas e banco de horas). Nunca anexe arquivos reais ao relato; use dados fictícios.

## Proteções existentes

- **Autenticação:** JWT HS256 com segredo obrigatório, validade de 8h e revogação imediata (troca de senha, perfil ou e-mail, desativação e logout). Senhas em BCrypt.
- **Força bruta:** bloqueio progressivo por e-mail no login (429 com `Retry-After`) e limite por IP atrás de proxy confiável.
- **Autorização:** acesso por perfil (`ADMIN`, `GESTOR_RH`, `AUDITOR`) num único ponto (`SecurityConfig`).
- **Frontend:** token só em cookie `httpOnly` (`__Host-` em produção), CSP com `frame-ancestors 'none'`, HSTS e demais cabeçalhos de segurança.
- **Uploads:** limite de tamanho, de conteúdo descompactado (zip bomb) e de páginas de PDF; o repasse do Next recusa sem sessão antes de ler o corpo.
- **Serviço interno:** o extrator exige `X-API-Key` em produção, fica só em `127.0.0.1` e roda em container não-root e somente leitura.
- **Repositório:** Dependabot (alertas e atualizações), secret scanning com push protection, CodeQL e proteção do `main` com CI obrigatório.

Os detalhes de cada proteção estão no [CONTRIBUTING.md](../CONTRIBUTING.md).
