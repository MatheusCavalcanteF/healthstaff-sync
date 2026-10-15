import "server-only";

import { headers } from "next/headers";

import { cabecalhoIpConfigurado, ipDoCliente } from "@/lib/ip-cliente";
import { LimiteTentativas } from "@/lib/limite-tentativas";

/**
 * Tentativas de login sem acerto por IP: 20 a cada 15 minutos. Soma-se ao
 * limite por e-mail do backend (que vê só o IP do servidor do Next).
 */
export const limiteLoginPorIp = new LimiteTentativas(20, 15 * 60_000);

/** Cabeçalho que o proxy reverso confiável define (`HEALTHSTAFF_CABECALHO_IP`); vazio = limite por IP desligado. */
const CABECALHO_IP = cabecalhoIpConfigurado(process.env.HEALTHSTAFF_CABECALHO_IP);

/** IP de quem chama, ou `null` quando o limite por IP não se aplica (ver `lib/ip-cliente.ts`). */
export async function chaveDeIp(): Promise<string | null> {
  if (!CABECALHO_IP) {
    return null;
  }
  const cabecalhos = await headers();
  return ipDoCliente(CABECALHO_IP, (nome) => cabecalhos.get(nome));
}

/** "45 s" ou "3 min" (arredondado para cima). */
export function formatarEspera(segundos: number): string {
  return segundos < 60 ? `${segundos} s` : `${Math.ceil(segundos / 60)} min`;
}
