-- =====================================================================
-- Revogação de sessões: o token leva a versão das credenciais do usuário
-- (claim "ver"). Trocar senha, e-mail ou perfil, ou desativar a conta,
-- incrementa a versão, e os tokens emitidos antes deixam de valer na hora.
--
-- Tokens emitidos antes desta migração não têm "ver": quem estiver logado
-- faz login de novo uma vez.
-- =====================================================================

alter table usuario add column versao_credenciais integer not null default 0;
