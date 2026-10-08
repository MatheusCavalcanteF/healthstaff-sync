import { NextResponse, type NextRequest } from "next/server";

import { COOKIE_SESSAO } from "@/lib/auth";

/**
 * Checagem otimista: sem o cookie da sessão, vai para o login (guardando a
 * página pedida). Não valida o token; isso é do backend, e um token vencido ou
 * revogado leva ao login quando a primeira chamada à API responder 401.
 */
export function proxy(requisicao: NextRequest) {
  const { pathname, search } = requisicao.nextUrl;
  if (pathname === "/login" || requisicao.cookies.has(COOKIE_SESSAO)) {
    return NextResponse.next();
  }
  const login = new URL("/login", requisicao.url);
  if (pathname !== "/") {
    login.searchParams.set("proximo", pathname + search);
  }
  return NextResponse.redirect(login);
}

export const config = {
  // Fora: o repasse /api/backend (responde 401 em JSON, e o proxy bufferizaria
  // uploads acima de 10 MB) e os arquivos estáticos.
  matcher: ["/((?!api/|_next/static|_next/image|favicon.ico).*)"],
};
