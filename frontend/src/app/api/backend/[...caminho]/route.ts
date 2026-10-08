import type { NextRequest } from "next/server";

import { API_URL } from "@/lib/api/client";
import { lerToken } from "@/lib/sessao";

/**
 * Repasse do navegador para o java-backend (`/api/backend/x` -> `{API_URL}/x`),
 * acrescentando o token do cookie httpOnly. Assim o JavaScript da página nunca
 * vê o token, e o navegador só fala com a própria origem.
 */

const CABECALHOS_DA_REQUISICAO = ["accept", "content-type"];
const CABECALHOS_DA_RESPOSTA = ["content-type", "www-authenticate"];

function problema(status: number, codigo: string, detail: string): Response {
  return Response.json({ status, title: "Erro no repasse", detail, codigo }, {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

async function repassar(requisicao: NextRequest, ctx: RouteContext<"/api/backend/[...caminho]">) {
  // Defesa extra além do SameSite=Lax do cookie: nada de chamada disparada por outro site.
  if (requisicao.headers.get("sec-fetch-site") === "cross-site") {
    return problema(403, "ACESSO_NEGADO", "requisição de outra origem");
  }

  const { caminho } = await ctx.params;
  // "..", "%2F" etc. sairiam de /api/v1 (ex.: /api/backend/../actuator/env).
  if (caminho.some((parte) => parte === "." || parte === ".." || /[/\\]/.test(parte))) {
    return problema(400, "PARAMETRO_INVALIDO", "caminho inválido");
  }
  const destino = `${API_URL}/${caminho.map(encodeURIComponent).join("/")}${requisicao.nextUrl.search}`;

  const cabecalhos = new Headers();
  for (const nome of CABECALHOS_DA_REQUISICAO) {
    const valor = requisicao.headers.get(nome);
    if (valor) cabecalhos.set(nome, valor);
  }
  const token = await lerToken();
  if (token) cabecalhos.set("authorization", `Bearer ${token}`);

  const comCorpo = requisicao.method !== "GET" && requisicao.method !== "HEAD";
  let resposta: Response;
  try {
    resposta = await fetch(destino, {
      method: requisicao.method,
      headers: cabecalhos,
      // Bufferizado (o backend limita a 20 MB): mais simples que repassar o stream.
      body: comCorpo ? await requisicao.arrayBuffer() : undefined,
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(130_000), // importações aguardam o extrator
    });
  } catch {
    return problema(502, "SEM_CONEXAO", "Não foi possível falar com o backend. Verifique se ele está no ar.");
  }

  const devolvidos = new Headers();
  for (const nome of CABECALHOS_DA_RESPOSTA) {
    const valor = resposta.headers.get(nome);
    if (valor) devolvidos.set(nome, valor);
  }
  // Location do backend aponta para o endereço interno; o navegador recebe o do repasse.
  const local = resposta.headers.get("location");
  if (local) {
    devolvidos.set("location", local.startsWith(API_URL) ? `/api/backend${local.slice(API_URL.length)}` : local);
  }
  return new Response(resposta.body, { status: resposta.status, headers: devolvidos });
}

export { repassar as GET, repassar as POST, repassar as PUT, repassar as PATCH, repassar as DELETE };
