import assert from "node:assert/strict";
import { test } from "node:test";

import { recusaDoRepasse, type PedidoDeRepasse } from "./regras-repasse.ts";

const LIMITE = 20 * 1024 * 1024;

function pedido(alteracoes: Partial<PedidoDeRepasse> = {}): PedidoDeRepasse {
  return {
    metodo: "POST",
    secFetchSite: "same-origin",
    caminho: ["importacoes", "escalas"],
    temSessao: true,
    contentLength: "1024",
    ...alteracoes,
  };
}

test("upload dentro do limite, com sessão, passa", () => {
  assert.equal(recusaDoRepasse(pedido(), LIMITE), null);
  assert.equal(recusaDoRepasse(pedido({ contentLength: String(LIMITE) }), LIMITE), null);
});

test("sem sessão: 401 antes de ler o corpo, mesmo com upload enorme", () => {
  const recusa = recusaDoRepasse(pedido({ temSessao: false, contentLength: String(10 * LIMITE) }), LIMITE);
  assert.equal(recusa?.status, 401);
  assert.equal(recusa?.codigo, "NAO_AUTENTICADO");
});

test("Content-Length acima do limite: 413", () => {
  const recusa = recusaDoRepasse(pedido({ contentLength: String(LIMITE + 1) }), LIMITE);
  assert.deepEqual(recusa, {
    status: 413,
    codigo: "ARQUIVO_MUITO_GRANDE",
    detail: "o envio passa do limite de 20 MB",
  });
});

test("corpo sem Content-Length (chunked): 411", () => {
  assert.equal(recusaDoRepasse(pedido({ contentLength: null }), LIMITE)?.status, 411);
});

test("Content-Length inválido: 400", () => {
  for (const invalido of ["abc", "-1", "1.5", "99999999999999999999"]) {
    assert.equal(recusaDoRepasse(pedido({ contentLength: invalido }), LIMITE)?.status, 400, invalido);
  }
});

test("GET não precisa de Content-Length, mas precisa de sessão", () => {
  assert.equal(recusaDoRepasse(pedido({ metodo: "GET", contentLength: null }), LIMITE), null);
  assert.equal(recusaDoRepasse(pedido({ metodo: "GET", temSessao: false }), LIMITE)?.status, 401);
});

test("outro site e caminho com '..' continuam recusados", () => {
  assert.equal(recusaDoRepasse(pedido({ secFetchSite: "cross-site" }), LIMITE)?.status, 403);
  assert.equal(recusaDoRepasse(pedido({ caminho: ["..", "actuator", "env"] }), LIMITE)?.status, 400);
  assert.equal(recusaDoRepasse(pedido({ caminho: ["a%2F..", "b\\c"] }), LIMITE)?.status, 400);
});
