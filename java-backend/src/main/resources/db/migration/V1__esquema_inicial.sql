-- =====================================================================
-- HealthStaff Sync - esquema inicial
--
-- Fluxo: o extrator (Python) devolve lotes; cada lote vira uma linha em
-- "importacao". Escalas são versionadas por setor + competência (a mais
-- recente é a vigente). Colaboradores sem matrícula ficam como cadastro
-- provisório, identificado pelo nome normalizado, até a matrícula aparecer.
--
-- Datas/horas das marcações são locais (timestamp sem fuso), como o
-- extrator envia; instantes de auditoria usam timestamptz.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Importações (um lote do extrator = uma importação)
-- ---------------------------------------------------------------------
create table importacao (
    id                  uuid         primary key,          -- idLote gerado pelo extrator
    tipo_documento      varchar(30)  not null,             -- ESCALA_PDF, ESCALA_EXCEL, PONTO_EXCEL, PONTO_CSV
    nome_arquivo        varchar(255) not null,
    hash_sha256         varchar(64)  not null,
    tamanho_bytes       bigint       not null,
    total_paginas       integer,
    extrator            varchar(60)  not null,
    versao_extrator     varchar(20)  not null,
    versao_contrato     varchar(10)  not null,
    periodo_inicio      date         not null,
    periodo_fim         date         not null,
    gerado_em           timestamp    not null,             -- hora local do extrator
    importado_em        timestamptz  not null default now(),
    total_colaboradores integer      not null,
    total_plantoes      integer      not null,
    possui_erros        boolean      not null,
    -- O mesmo arquivo enviado de novo não gera nova importação.
    constraint uk_importacao_arquivo unique (tipo_documento, hash_sha256),
    constraint ck_importacao_periodo check (periodo_fim >= periodo_inicio)
);

create table inconsistencia (
    id                bigint       generated always as identity primary key,
    importacao_id     uuid         not null references importacao (id) on delete cascade,
    codigo            varchar(60)  not null,
    severidade        varchar(10)  not null,
    mensagem          text         not null,
    pagina            integer,
    aba               varchar(100),
    linha             integer,
    setor             varchar(255),
    matricula         varchar(30),
    nome_colaborador  varchar(255),
    data              date,
    valor_bruto       text
);
create index ix_inconsistencia_importacao on inconsistencia (importacao_id);

-- ---------------------------------------------------------------------
-- Cadastros
-- ---------------------------------------------------------------------
create table setor (
    id     bigint       generated always as identity primary key,
    nome   varchar(255) not null,
    chave  varchar(255) not null,                          -- nome normalizado (sem acento, maiúsculo)
    constraint uk_setor_chave unique (chave)
);

create table colaborador (
    id                 bigint       generated always as identity primary key,
    matricula          varchar(30),                        -- nula = cadastro provisório
    nome               varchar(255) not null,
    nome_normalizado   varchar(255) not null,
    registro_conselho  varchar(30),                        -- COREN, CRM...
    cargo              varchar(255),
    jornada            varchar(30),
    criado_em          timestamptz  not null default now(),
    atualizado_em      timestamptz  not null default now(),
    constraint uk_colaborador_matricula unique (matricula)
);
-- No máximo um cadastro provisório por nome normalizado.
create unique index uk_colaborador_provisorio on colaborador (nome_normalizado) where matricula is null;
create index ix_colaborador_nome_normalizado on colaborador (nome_normalizado);

-- ---------------------------------------------------------------------
-- Escalas (versionadas) e plantões
-- ---------------------------------------------------------------------
create table escala (
    id             bigint   generated always as identity primary key,
    setor_id       bigint   not null references setor (id),
    competencia    date     not null,                      -- primeiro dia do mês
    versao         integer  not null,
    vigente        boolean  not null,
    importacao_id  uuid     not null references importacao (id),
    constraint uk_escala_versao unique (setor_id, competencia, versao),
    constraint ck_escala_competencia check (extract(day from competencia) = 1),
    constraint ck_escala_versao check (versao >= 1)
);
-- Exatamente uma versão vigente por setor + competência.
create unique index uk_escala_vigente on escala (setor_id, competencia) where vigente;

create table plantao (
    id               bigint       generated always as identity primary key,
    escala_id        bigint       not null references escala (id) on delete cascade,
    -- O setor pertence ao plantão (o folguista cobre setores diferentes).
    setor_id         bigint       not null references setor (id),
    colaborador_id   bigint       not null references colaborador (id),
    data             date         not null,
    tipo             varchar(20)  not null,                -- PLANTAO, FOLGA, FERIAS, LICENCA, AFASTAMENTO, FALTA, DESCONHECIDO
    turno            varchar(20),
    codigo_original  text         not null,                -- texto bruto da célula ("PN", "FÉRIAS A PARTIR DE ...")
    inicio           timestamp,
    fim              timestamp,
    folguista        boolean      not null default false,  -- cobertura por folguista/ferista
    observacao       varchar(255),
    constraint ck_plantao_horario check (
        (tipo = 'PLANTAO' and inicio is not null and fim is not null and fim > inicio)
        or (tipo <> 'PLANTAO' and inicio is null and fim is null)
    )
);
create index ix_plantao_escala on plantao (escala_id);
-- Consultas das regras (interjornada, sobreposição) por pessoa no tempo.
create index ix_plantao_colaborador_inicio on plantao (colaborador_id, inicio);

-- ---------------------------------------------------------------------
-- Banco de horas (um retrato por importação do relatório do Two)
-- ---------------------------------------------------------------------
create table saldo_banco_horas (
    id               bigint       generated always as identity primary key,
    importacao_id    uuid         not null references importacao (id) on delete cascade,
    colaborador_id   bigint       not null references colaborador (id),
    minutos          integer      not null,                -- positivo = crédito, negativo = débito
    data_referencia  date         not null,
    inicio_apuracao  date,
    departamento     varchar(255),                         -- como veio no Two
    constraint uk_saldo_importacao_colaborador unique (importacao_id, colaborador_id)
);
create index ix_saldo_colaborador_data on saldo_banco_horas (colaborador_id, data_referencia desc);
