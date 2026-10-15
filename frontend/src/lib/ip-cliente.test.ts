import assert from "node:assert/strict";
import { test } from "node:test";

import { cabecalhoIpConfigurado, ipDoCliente } from "./ip-cliente.ts";

const FORJADO = { "x-real-ip": "6.6.6.6", "x-forwarded-for": "6.6.6.6, 10.0.0.7" };

function leitor(cabecalhos: Record<string, string>) {
  return (nome: string) => cabecalhos[nome] ?? null;
}

test("sem configuração: limite por IP desligado, nenhum cabeçalho é lido", () => {
  for (const vazio of [undefined, "", "   "]) {
    assert.equal(cabecalhoIpConfigurado(vazio), null);
  }
  assert.equal(ipDoCliente(null, leitor(FORJADO)), null);
});

test("x-real-ip: usa só ele, ignora o X-Forwarded-For", () => {
  const cabecalho = cabecalhoIpConfigurado("X-Real-IP");
  assert.equal(cabecalho, "x-real-ip");
  assert.equal(ipDoCliente(cabecalho, leitor({ "x-real-ip": " 10.0.0.5 ", "x-forwarded-for": "6.6.6.6" })), "10.0.0.5");
});

test("x-forwarded-for: usa o último endereço (o do proxy), ignora o X-Real-IP", () => {
  const cabecalho = cabecalhoIpConfigurado("x-forwarded-for");
  assert.equal(ipDoCliente(cabecalho, leitor(FORJADO)), "10.0.0.7");
  assert.equal(ipDoCliente(cabecalho, leitor({ "x-forwarded-for": "10.0.0.8" })), "10.0.0.8");
});

test("cabeçalho configurado ausente ou vazio: não conta tentativa (sem chave compartilhada)", () => {
  assert.equal(ipDoCliente("x-real-ip", leitor({ "x-forwarded-for": "10.0.0.7" })), null);
  assert.equal(ipDoCliente("x-forwarded-for", leitor({ "x-real-ip": "10.0.0.5" })), null);
  assert.equal(ipDoCliente("x-forwarded-for", leitor({ "x-forwarded-for": "10.0.0.7, " })), null);
  assert.equal(ipDoCliente("x-real-ip", leitor({ "x-real-ip": "  " })), null);
});

test("valor desconhecido é erro de configuração", () => {
  assert.throws(() => cabecalhoIpConfigurado("cf-connecting-ip"), /HEALTHSTAFF_CABECALHO_IP inválido/);
});
