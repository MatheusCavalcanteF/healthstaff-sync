import assert from "node:assert/strict";
import { afterEach, test } from "node:test";

import nextConfig from "../../next.config.ts";

const ambienteOriginal = process.env.NODE_ENV;

afterEach(() => {
  (process.env as Record<string, string | undefined>).NODE_ENV = ambienteOriginal;
});

async function cabecalhos(ambiente: "development" | "production"): Promise<Map<string, string>> {
  (process.env as Record<string, string | undefined>).NODE_ENV = ambiente;
  assert.ok(nextConfig.headers, "next.config.ts sem headers()");
  const regras = await nextConfig.headers();
  assert.equal(regras.length, 1);
  assert.equal(regras[0].source, "/:path*");
  return new Map(regras[0].headers.map(({ key, value }) => [key, value]));
}

test("produção: CSP estrita para a variante sem nonce, HSTS e demais cabeçalhos", async () => {
  const h = await cabecalhos("production");
  const csp = h.get("Content-Security-Policy") ?? "";

  assert.match(csp, /frame-ancestors 'none'/);
  assert.match(csp, /object-src 'none'/);
  assert.match(csp, /default-src 'self'/);
  assert.match(csp, /upgrade-insecure-requests/);
  assert.doesNotMatch(csp, /unsafe-eval/);
  assert.equal(h.get("X-Content-Type-Options"), "nosniff");
  assert.equal(h.get("Referrer-Policy"), "strict-origin-when-cross-origin");
  assert.match(h.get("Permissions-Policy") ?? "", /camera=\(\)/);
  assert.match(h.get("Strict-Transport-Security") ?? "", /^max-age=\d+/);
});

test("desenvolvimento: sem HSTS nem upgrade-insecure-requests, com unsafe-eval para o React", async () => {
  const h = await cabecalhos("development");
  const csp = h.get("Content-Security-Policy") ?? "";

  assert.equal(h.has("Strict-Transport-Security"), false);
  assert.doesNotMatch(csp, /upgrade-insecure-requests/);
  assert.match(csp, /script-src 'self' 'unsafe-inline' 'unsafe-eval'/);
  assert.match(csp, /frame-ancestors 'none'/);
});
