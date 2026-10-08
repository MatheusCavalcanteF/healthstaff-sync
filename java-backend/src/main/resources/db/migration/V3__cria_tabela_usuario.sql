-- =====================================================================
-- Usuários da aplicação (login com e-mail e senha; a API usa JWT).
--
-- Nenhum usuário é inserido aqui: uma senha fixa no histórico do Git
-- valeria em todo ambiente que rodasse esta migração. O primeiro
-- administrador é criado na subida do backend a partir de
-- HEALTHSTAFF_ADMIN_EMAIL / HEALTHSTAFF_ADMIN_SENHA (ver AdminInicial).
-- =====================================================================

create table usuario (
    id            uuid          primary key,             -- "sub" do token: não sequencial
    nome          varchar(255)  not null,
    email         varchar(255)  not null,                -- sempre em minúsculas
    senha_hash    varchar(100)  not null,                -- BCrypt
    perfil        varchar(20)   not null,
    ativo         boolean       not null default true,
    data_criacao  timestamptz   not null default now(),
    constraint uk_usuario_email unique (email),
    constraint ck_usuario_email_minusculo check (email = lower(email)),
    constraint ck_usuario_perfil check (perfil in ('ADMIN', 'GESTOR_RH', 'AUDITOR'))
);
