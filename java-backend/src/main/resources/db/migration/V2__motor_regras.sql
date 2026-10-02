-- =====================================================================
-- Motor de regras: análises por competência, consolidação de horas e
-- inconformidades (alertas de regra de negócio, distintos das
-- inconsistências de extração).
--
-- Cada execução do motor gera uma nova análise da competência; só a mais
-- recente é vigente. As anteriores ficam como histórico.
-- =====================================================================

create table analise (
    id                       bigint       generated always as identity primary key,
    competencia              date         not null,          -- primeiro dia do mês
    executada_em             timestamptz  not null default now(),
    gatilho                  varchar(30)  not null,          -- IMPORTACAO_ESCALA, IMPORTACAO_PONTO, MANUAL
    importacao_id            uuid         references importacao (id) on delete set null,
    vigente                  boolean      not null,
    colaboradores_analisados integer      not null,
    total_inconformidades    integer      not null,
    constraint ck_analise_competencia check (extract(day from competencia) = 1)
);
create unique index uk_analise_vigente on analise (competencia) where vigente;

create table consolidacao_horas (
    id                        bigint       generated always as identity primary key,
    analise_id                bigint       not null references analise (id) on delete cascade,
    colaborador_id            bigint       not null references colaborador (id),
    jornada                   varchar(30),
    categoria                 varchar(30),                   -- ENFERMEIRO, TECNICO_ENFERMAGEM, ADMINISTRATIVO, OUTRO
    minutos_escalados         integer      not null,         -- horas de plantão
    folgas_organizacionais    integer      not null,         -- FGs na escala
    folgas_organizacionais_creditadas integer not null,      -- FGs dentro da cota da categoria
    minutos_credito_fg        integer      not null,         -- crédito das FGs creditadas
    minutos_cumpridos         integer      not null,         -- plantão + crédito de FG
    minutos_esperados         integer,                       -- carga contratual (nulo: jornada sem carga conhecida)
    diferenca_minutos         integer,                       -- cumpridos - esperados
    saldo_minutos             integer,                       -- saldo do Two (nulo: não está no Two)
    saldo_data_referencia     date,
    saldo_projetado_minutos   integer,                       -- saldo + diferença
    plantoes                  integer      not null,
    dias_ausencia             integer      not null,         -- férias, licença, afastamento
    folgas_banco_horas        integer      not null,
    constraint uk_consolidacao_colaborador unique (analise_id, colaborador_id)
);

create table inconformidade (
    id                       bigint       generated always as identity primary key,
    analise_id               bigint       not null references analise (id) on delete cascade,
    colaborador_id           bigint       not null references colaborador (id),
    tipo                     varchar(40)  not null,
    gravidade                varchar(10)  not null,          -- CRITICA, ALTA, MEDIA
    data                     date         not null,
    -- Plantões envolvidos; versões antigas de escala podem ser apagadas sem perder o alerta.
    plantao_id               bigint       references plantao (id) on delete set null,
    plantao_relacionado_id   bigint       references plantao (id) on delete set null,
    valor_minutos            integer,
    limite_minutos           integer,
    descricao                text         not null
);
create index ix_inconformidade_analise on inconformidade (analise_id);
create index ix_inconformidade_colaborador on inconformidade (colaborador_id, data);
