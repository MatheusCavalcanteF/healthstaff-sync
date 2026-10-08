import "server-only";

import { cookies } from "next/headers";

import { COOKIE_SESSAO, type UsuarioSessao } from "@/lib/auth";
import type { Perfil } from "@/types/api";

export async function lerToken(): Promise<string | undefined> {
  return (await cookies()).get(COOKIE_SESSAO)?.value;
}

/**
 * Lê nome e perfil do payload do JWT **sem conferir a assinatura**: serve só
 * para exibir. Toda decisão de acesso é do backend, que valida o token.
 */
export async function lerUsuarioSessao(): Promise<UsuarioSessao | null> {
  const token = await lerToken();
  const payload = token?.split(".")[1];
  if (!payload) {
    return null;
  }
  try {
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      nome?: unknown;
      perfil?: unknown;
      exp?: unknown;
    };
    if (typeof claims.exp === "number" && claims.exp * 1000 <= Date.now()) {
      return null;
    }
    if (typeof claims.nome !== "string" || !["ADMIN", "GESTOR_RH", "AUDITOR"].includes(String(claims.perfil))) {
      return null;
    }
    return { nome: claims.nome, perfil: claims.perfil as Perfil };
  } catch {
    return null;
  }
}

/** Só em Server Actions ou Route Handlers (o Next não grava cookie durante a renderização). */
export async function gravarSessao(token: string, expiraEm: string): Promise<void> {
  (await cookies()).set(COOKIE_SESSAO, token, {
    httpOnly: true, // o JavaScript da página nunca vê o token (XSS não o rouba)
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax", // outro site não consegue disparar POST com o cookie
    path: "/",
    expires: new Date(expiraEm), // some junto com a validade do token
  });
}

export async function encerrarSessao(): Promise<void> {
  (await cookies()).delete(COOKIE_SESSAO);
}
