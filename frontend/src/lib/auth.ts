import type { Perfil } from "@/types/api";

/** Quem está logado, para a interface (nome no menu, esconder ações do perfil). */
export interface UsuarioSessao {
  id: string;
  nome: string;
  perfil: Perfil;
}

/** Cookie httpOnly com o JWT do backend (lido só no servidor do Next). */
export const COOKIE_SESSAO = "hs_sessao";

export const PERFIS: Perfil[] = ["ADMIN", "GESTOR_RH", "AUDITOR"];

export const ROTULO_PERFIL: Record<Perfil, string> = {
  ADMIN: "Administrador",
  GESTOR_RH: "Gestão de RH",
  AUDITOR: "Auditoria",
};

/**
 * Espelha a regra do backend (só para a interface; quem barra é a API):
 * importar e reprocessar é de ADMIN e GESTOR_RH.
 */
export function podeImportar(perfil: Perfil | undefined): boolean {
  return perfil === "ADMIN" || perfil === "GESTOR_RH";
}

/**
 * Para onde ir depois do login. Só caminhos internos: um `proximo` como
 * "//site.com" ou "https://..." viraria redirecionamento para fora (phishing).
 */
export function destinoSeguro(proximo: string | null | undefined): string {
  if (!proximo || !proximo.startsWith("/") || proximo.startsWith("//") || proximo.startsWith("/\\")) {
    return "/";
  }
  return proximo.startsWith("/login") ? "/" : proximo;
}
