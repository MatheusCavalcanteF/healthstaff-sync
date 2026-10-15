"use server";

import { redirect } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import { autenticar, encerrarSessaoNoBackend } from "@/lib/api/endpoints";
import { destinoSeguro } from "@/lib/auth";
import { chaveDeIp, formatarEspera, limiteLoginPorIp } from "@/lib/limite-login";
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

  // Só com proxy confiável configurado (sem IP confiável, vale apenas o limite por
  // e-mail do backend). Reservada antes de chamar o backend; fica contada só se errar.
  const ip = await chaveDeIp();
  const reserva = ip ? limiteLoginPorIp.reservar(ip) : { permitida: true, esperaSegundos: 0 };
  if (!reserva.permitida) {
    return {
      erro: `Muitas tentativas de login a partir desta rede. Tente de novo em ${formatarEspera(reserva.esperaSegundos)}.`,
      email,
    };
  }

  try {
    const { token, expiraEm } = await autenticar(email, senha);
    await gravarSessao(token, expiraEm);
    if (ip) limiteLoginPorIp.devolver(ip);
  } catch (erro) {
    if (erro instanceof ApiError && erro.codigo === "CREDENCIAIS_INVALIDAS") {
      return { erro: "E-mail ou senha inválidos.", email };
    }
    if (erro instanceof ApiError && erro.codigo === "MUITAS_TENTATIVAS") {
      const espera = erro.problema.tentarNovamenteEmSegundos;
      return {
        erro: `Muitas tentativas de login para este e-mail. Tente de novo${espera ? ` em ${formatarEspera(espera)}` : " mais tarde"}.`,
        email,
      };
    }
    // Demais falhas não são palpite de senha: não contam contra a rede.
    if (ip) limiteLoginPorIp.devolver(ip);
    if (!(erro instanceof ApiError)) {
      throw erro;
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
  try {
    // Revoga o token no servidor: mesmo copiado antes, ele deixa de valer agora.
    await encerrarSessaoNoBackend();
  } catch {
    // Token já vencido ou revogado (o 401 vira redirecionamento no apiServidor) ou
    // backend fora do ar: o cookie sai de qualquer jeito.
  }
  await encerrarSessao();
  redirect("/login");
}
