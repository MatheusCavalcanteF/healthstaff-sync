import type { GatilhoAnalise, Gravidade, InstanteISO } from "@/types/api";

const NUMERO = new Intl.NumberFormat("pt-BR");

/** Horário do hospital, independente do fuso do servidor que renderiza a página. */
const DATA_HORA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  dateStyle: "short",
  timeStyle: "short",
});

/**
 * Minutos em horas no padrão das mensagens do backend: 2682 -> "44h42",
 * 61615 -> "1.026h55", 600 -> "10h". Com `comSinal`, positivos ganham "+".
 */
export function formatarHoras(minutos: number, { comSinal = false } = {}): string {
  const sinal = minutos < 0 ? "-" : comSinal && minutos > 0 ? "+" : "";
  const absoluto = Math.abs(Math.round(minutos));
  const horas = Math.floor(absoluto / 60);
  const resto = absoluto % 60;
  return `${sinal}${NUMERO.format(horas)}h${resto === 0 ? "" : String(resto).padStart(2, "0")}`;
}

export function formatarNumero(valor: number): string {
  return NUMERO.format(valor);
}

/** 0.4567 -> "46%". Divisor zero resulta em "0%". */
export function formatarPercentual(parte: number, total: number): string {
  return total === 0 ? "0%" : `${Math.round((parte / total) * 100)}%`;
}

export function formatarDataHora(instante: InstanteISO): string {
  return DATA_HORA.format(new Date(instante));
}

export const ROTULO_GRAVIDADE: Record<Gravidade, string> = {
  CRITICA: "Crítica",
  ALTA: "Alta",
  MEDIA: "Média",
};

export const ROTULO_GATILHO: Record<GatilhoAnalise, string> = {
  IMPORTACAO_ESCALA: "importação de escala",
  IMPORTACAO_PONTO: "importação do banco de horas",
  MANUAL: "reprocessamento manual",
};
