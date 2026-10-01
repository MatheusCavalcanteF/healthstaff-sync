# syntax=docker/dockerfile:1
#
# Microsserviço interno de extração (python-extractor / FastAPI).
# Contexto de build: raiz do repositório (ver .dockerignore, que só libera o
# código-fonte do extrator).
#
#   docker compose up --build

ARG PYTHON_IMAGE=python:3.13-slim

# ---------------------------------------------------------------------------
# Etapa 1: dependências de produção num venv isolado
# ---------------------------------------------------------------------------
FROM ${PYTHON_IMAGE} AS dependencias

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}"

# Só o requirements de produção: pytest/pandas ficam fora da imagem.
COPY python-extractor/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --only-binary=:all: -r /tmp/requirements.txt

# ---------------------------------------------------------------------------
# Etapa 2: imagem final, sem pip cache, sem testes, sem usuário root
# ---------------------------------------------------------------------------
FROM ${PYTHON_IMAGE} AS runtime

LABEL org.opencontainers.image.title="healthstaff-extractor" \
      org.opencontainers.image.description="Extração de escalas (PDF/Excel) e Banco de Horas do Two (Excel/CSV)" \
      org.opencontainers.image.source="https://github.com/MatheusCavalcanteF/healthstaff-sync"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:${PATH}" \
    PORTA=8000

RUN groupadd --system --gid 10001 extractor \
 && useradd --system --uid 10001 --gid extractor --no-create-home \
            --home-dir /nonexistent --shell /usr/sbin/nologin extractor

COPY --from=dependencias /opt/venv /opt/venv

WORKDIR /app
# Código pertence ao root e é só leitura para o usuário do serviço.
COPY python-extractor/main.py ./
COPY python-extractor/api ./api
COPY python-extractor/extractors ./extractors
COPY python-extractor/models ./models
COPY python-extractor/utils ./utils
COPY python-extractor/schema ./schema

USER extractor:extractor
EXPOSE 8000

# Imagem slim não tem curl: o healthcheck usa o próprio Python.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["python", "-c", "import os, sys, urllib.request; r = urllib.request.urlopen(f'http://127.0.0.1:{os.environ.get(\"PORTA\", \"8000\")}/health', timeout=4); sys.exit(0 if r.status == 200 else 1)"]

# Processo único; escalar com réplicas do container, não com workers.
CMD ["sh", "-c", "exec uvicorn main:app --host 0.0.0.0 --port ${PORTA} --no-server-header"]
