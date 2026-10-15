import "server-only";

import { headers } from "next/headers";

import { LimiteTentativas } from "@/lib/limite-tentativas";

/**
 * Tentativas de login sem acerto por IP: 20 a cada 15 minutos. Soma-se ao
 * limite por e-mail do backend (que vê só o IP do servidor do Next).
 */
export const limiteLoginPorIp = new LimiteTentativas(20, 15 * 60_000);

/**
 * IP de quem chama, como o proxy reverso informou: o `X-Real-IP` ou o último
 * endereço do `X-Forwarded-For` (o que o proxy acrescentou). Sem proxy na
 * frente, o cliente pode forjar esses cabeçalhos e contornar este limite; o
 * limite por e-mail do backend continua valendo.
 */
export async function ipDoCliente(): Promise<string> {
  const cabecalhos = await headers();
  const real = cabecalhos.get("x-real-ip")?.trim();
  const encaminhado = cabecalhos.get("x-forwarded-for")?.split(",").at(-1)?.trim();
  return real || encaminhado || "desconhecido";
}

/** "45 s" ou "3 min" (arredondado para cima). */
export function formatarEspera(segundos: number): string {
  return segundos < 60 ? `${segundos} s` : `${Math.ceil(segundos / 60)} min`;
}
