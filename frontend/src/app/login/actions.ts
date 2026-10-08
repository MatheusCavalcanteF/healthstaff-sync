"use server";

import { redirect } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import { autenticar } from "@/lib/api/endpoints";
import { destinoSeguro } from "@/lib/auth";
import { encerrarSessao, gravarSessao } from "@/lib/sessao";

export interface EstadoLogin {
  erro?: string;
  /** Devolvido ao formulário para a pessoa não redigitar o e-mail. */
  email?: string;
}

export async function entrar(_anterior: EstadoLogin, formulario: FormData): Promise<EstadoLogin> {
  const email = String(formulario.get("email") ?? "").trim();
  const senha = String(formulario.get("senha") ?? "");
  const destino = destinoSeguro(String(formulario.get("proximo") ?? ""));
  if (!email || !senha) {
    return { erro: "Informe o e-mail e a senha.", email };
  }

  try {
    const { token, expiraEm } = await autenticar(email, senha);
    await gravarSessao(token, expiraEm);
  } catch (erro) {
    if (!(erro instanceof ApiError)) {
      throw erro;
    }
    if (erro.codigo === "CREDENCIAIS_INVALIDAS") {
      return { erro: "E-mail ou senha inválidos.", email };
    }
    if (erro.semConexao) {
      return { erro: "O servidor não respondeu. Verifique se o backend está no ar e tente novamente.", email };
    }
    return { erro: erro.message, email };
  }
  // Fora do try: o redirect do Next funciona lançando uma exceção.
  redirect(destino);
}

export async function sair(): Promise<void> {
  await encerrarSessao();
  redirect("/login");
}
