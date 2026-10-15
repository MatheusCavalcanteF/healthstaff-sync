import type { NextRequest } from "next/server";

import { API_URL } from "@/lib/api/client";
import { recusaDoRepasse, temCorpo } from "@/lib/regras-repasse";
import { lerToken } from "@/lib/sessao";

/**
 * Repasse do navegador para o java-backend (`/api/backend/x` -> `{API_URL}/x`),
 * acrescentando o token do cookie httpOnly. Assim o JavaScript da página nunca
 * vê o token, e o navegador só fala com a própria origem.
 */

const CABECALHOS_DA_REQUISICAO = ["accept", "content-type"];

/** Mesmo limite do backend e do extrator (`EXTRACTOR_MAX_UPLOAD_MB`, padrão 20). */
const LIMITE_BYTES = Number(process.env.EXTRACTOR_MAX_UPLOAD_MB ?? "20") * 1024 * 1024;
const CABECALHOS_DA_RESPOSTA = ["content-type", "www-authenticate"];

function problema(status: number, codigo: string, detail: string): Response {
  return Response.json({ status, title: "Erro no repasse", detail, codigo }, {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

async function repassar(requisicao: NextRequest, ctx: RouteContext<"/api/backend/[...caminho]">) {
  const { caminho } = await ctx.params;
  const token = await lerToken();
  // Tudo decidido pelos cabeçalhos: o corpo só é lido (e bufferizado) depois.
  const recusa = recusaDoRepasse(
    {
      metodo: requisicao.method,
      secFetchSite: requisicao.headers.get("sec-fetch-site"),
      caminho,
      temSessao: Boolean(token),
      contentLength: requisicao.headers.get("content-length"),
    },
    LIMITE_BYTES,
  );
  if (recusa) {
    return problema(recusa.status, recusa.codigo, recusa.detail);
  }
  const destino = `${API_URL}/${caminho.map(encodeURIComponent).join("/")}${requisicao.nextUrl.search}`;

  const cabecalhos = new Headers();
  for (const nome of CABECALHOS_DA_REQUISICAO) {
    const valor = requisicao.headers.get(nome);
    if (valor) cabecalhos.set(nome, valor);
  }
  cabecalhos.set("authorization", `Bearer ${token}`);

  const comCorpo = temCorpo(requisicao.method);
  let resposta: Response;
  try {
    resposta = await fetch(destino, {
      method: requisicao.method,
      headers: cabecalhos,
      // Bufferizado: mais simples que repassar o stream, e o tamanho já foi limitado acima.
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
