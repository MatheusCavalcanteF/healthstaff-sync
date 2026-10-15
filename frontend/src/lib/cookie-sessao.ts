/**
 * Nome e atributos do cookie da sessão (o JWT do backend). Sem aliases: roda
 * direto no `node --test`.
 *
 * Em produção, prefixo `__Host-`: o navegador só aceita o cookie com Secure,
 * Path=/ e sem Domain, então nem um subdomínio nem uma página em HTTP gravam
 * ou sobrescrevem a sessão. Em desenvolvimento (http://localhost), `hs_sessao`
 * sem Secure.
 */
export function cookieDaSessao(producao: boolean) {
  return {
    nome: producao ? "__Host-hs_sessao" : "hs_sessao",
    atributos: {
      httpOnly: true, // o JavaScript da página nunca vê o token (XSS não o rouba)
      secure: producao,
      sameSite: "lax" as const, // outro site não consegue disparar POST com o cookie
      path: "/",
      // Sem domain, de propósito: exigência do __Host- e cookie preso à origem exata.
    },
  };
}

export const COOKIE_SESSAO_ATUAL = cookieDaSessao(process.env.NODE_ENV === "production");
