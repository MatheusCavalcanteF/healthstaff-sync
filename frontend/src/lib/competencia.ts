import { format, isValid, parse } from "date-fns";
import { ptBR } from "date-fns/locale";

import type { Competencia } from "@/types/api";

/** Competência no formato da API: AAAA-MM (mês 01-12). */
const FORMATO = /^\d{4}-(0[1-9]|1[0-2])$/;

export function competenciaValida(valor: string): valor is Competencia {
  return FORMATO.test(valor) && isValid(parse(valor, "yyyy-MM", new Date()));
}

/** Competência do mês corrente, no fuso local. */
export function competenciaAtual(agora: Date = new Date()): Competencia {
  return format(agora, "yyyy-MM");
}

/** "2026-10" -> "Outubro de 2026". */
export function rotuloCompetencia(competencia: Competencia): string {
  const texto = format(parse(competencia, "yyyy-MM", new Date()), "MMMM 'de' yyyy", { locale: ptBR });
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

/** Extrai a competência de caminhos como /analises/2026-10/inconformidades. */
export function competenciaDaRota(caminho: string): Competencia | null {
  const trecho = caminho.match(/^\/analises\/([^/]+)/)?.[1];
  return trecho && competenciaValida(trecho) ? trecho : null;
}
