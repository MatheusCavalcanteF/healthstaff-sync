/**
 * O que o repasse `/api/backend` recusa **antes de ler o corpo** (que seria
 * bufferizado inteiro na memória do Next). Sem aliases: roda no `node --test`.
 */

export interface PedidoDeRepasse {
  metodo: string;
  /** Cabeçalho `Sec-Fetch-Site` (null em clientes que não o enviam). */
  secFetchSite: string | null;
  caminho: string[];
  temSessao: boolean;
  /** Cabeçalho `Content-Length` como veio. */
  contentLength: string | null;
}

export interface Recusa {
  status: number;
  codigo: string;
  detail: string;
}

export function temCorpo(metodo: string): boolean {
  return metodo !== "GET" && metodo !== "HEAD";
}

/** `null` = pode repassar. A ordem importa: nada de ler o corpo de quem nem tem sessão. */
export function recusaDoRepasse(pedido: PedidoDeRepasse, limiteBytes: number): Recusa | null {
  // Defesa extra além do SameSite=Lax do cookie: nada de chamada disparada por outro site.
  if (pedido.secFetchSite === "cross-site") {
    return { status: 403, codigo: "ACESSO_NEGADO", detail: "requisição de outra origem" };
  }
  // "..", "%2F" etc. sairiam de /api/v1 (ex.: /api/backend/../actuator/env).
  if (pedido.caminho.some((parte) => parte === "." || parte === ".." || /[/\\]/.test(parte))) {
    return { status: 400, codigo: "PARAMETRO_INVALIDO", detail: "caminho inválido" };
  }
  if (!pedido.temSessao) {
    return { status: 401, codigo: "NAO_AUTENTICADO", detail: "faça login para acessar a API" };
  }
  if (!temCorpo(pedido.metodo)) {
    return null;
  }
  // Sem Content-Length (corpo chunked) não há como limitar antes de ler; o
  // navegador sempre o envia em fetch com FormData ou JSON. Com ele, o Node lê
  // no máximo o que foi declarado.
  if (pedido.contentLength === null) {
    return { status: 411, codigo: "PARAMETRO_INVALIDO", detail: "envie o corpo com Content-Length" };
  }
  const tamanho = Number(pedido.contentLength);
  if (!Number.isSafeInteger(tamanho) || tamanho < 0) {
    return { status: 400, codigo: "PARAMETRO_INVALIDO", detail: "Content-Length inválido" };
  }
  if (tamanho > limiteBytes) {
    const mb = Math.floor(limiteBytes / (1024 * 1024));
    return { status: 413, codigo: "ARQUIVO_MUITO_GRANDE", detail: `o envio passa do limite de ${mb} MB` };
  }
  return null;
}
