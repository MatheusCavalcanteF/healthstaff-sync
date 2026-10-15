import assert from "node:assert/strict";
import { test } from "node:test";

import { LimiteTentativas } from "./limite-tentativas.ts";

function comRelogio(maximo: number, janelaMs: number) {
  const relogio = { agora: 1_000_000 };
  const limite = new LimiteTentativas(maximo, janelaMs, () => relogio.agora);
  return { limite, relogio };
}

test("permite até o máximo e recusa a seguinte com a espera em segundos", () => {
  const { limite, relogio } = comRelogio(3, 60_000);
  for (let i = 0; i < 3; i++) {
    assert.equal(limite.reservar("10.0.0.1").permitida, true);
  }
  relogio.agora += 20_500;

  assert.deepEqual(limite.reservar("10.0.0.1"), { permitida: false, esperaSegundos: 40 });
});

test("a janela reabre quando vence", () => {
  const { limite, relogio } = comRelogio(1, 60_000);
  limite.reservar("10.0.0.1");
  relogio.agora += 60_000;

  assert.equal(limite.reservar("10.0.0.1").permitida, true);
});

test("tentativa devolvida não conta (login aceito)", () => {
  const { limite } = comRelogio(2, 60_000);
  for (let i = 0; i < 5; i++) {
    assert.equal(limite.reservar("10.0.0.1").permitida, true);
    limite.devolver("10.0.0.1");
  }
});

test("chaves diferentes não se afetam", () => {
  const { limite } = comRelogio(1, 60_000);
  limite.reservar("10.0.0.1");

  assert.equal(limite.reservar("10.0.0.1").permitida, false);
  assert.equal(limite.reservar("10.0.0.2").permitida, true);
});

test("recusa não consome a janela de novo", () => {
  const { limite, relogio } = comRelogio(1, 60_000);
  limite.reservar("10.0.0.1");
  limite.reservar("10.0.0.1");
  limite.devolver("10.0.0.1"); // devolve a única tentativa que contou
  relogio.agora += 1;

  assert.equal(limite.reservar("10.0.0.1").permitida, true);
});
