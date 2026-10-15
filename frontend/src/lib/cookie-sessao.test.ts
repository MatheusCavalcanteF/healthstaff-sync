import assert from "node:assert/strict";
import { test } from "node:test";

import { cookieDaSessao } from "./cookie-sessao.ts";

test("produção: __Host- com Secure, Path=/ e sem Domain", () => {
  const { nome, atributos } = cookieDaSessao(true);

  assert.equal(nome, "__Host-hs_sessao");
  assert.equal(atributos.secure, true);
  assert.equal(atributos.path, "/");
  assert.equal("domain" in atributos, false);
  assert.equal(atributos.httpOnly, true);
  assert.equal(atributos.sameSite, "lax");
});

test("desenvolvimento: hs_sessao sem Secure (http://localhost)", () => {
  const { nome, atributos } = cookieDaSessao(false);

  assert.equal(nome, "hs_sessao");
  assert.equal(atributos.secure, false);
  assert.equal(atributos.httpOnly, true);
});
