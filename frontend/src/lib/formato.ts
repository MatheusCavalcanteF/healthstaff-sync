import { format, parseISO } from "date-fns";
import { ptBR } from "date-fns/locale";

import type { DataHoraISO, DataISO, GatilhoAnalise, Gravidade, InstanteISO } from "@/types/api";

const NUMERO = new Intl.NumberFormat("pt-BR");

/** Horário do hospital, independente do fuso do servidor que renderiza a página. */
const DATA_HORA = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  dateStyle: "short",
  timeStyle: "short",
});

/**
 * Minutos em horas no padrão das mensagens do backend: 2682 -> "44h42",
 * 61615 -> "1.026h55", 600 -> "10h". Com `comSinal`, positivos ganham "+";
 * com `minutosSempre`, hora cheia mantém os minutos ("10h00", para alinhar em colunas).
 */
export function formatarHoras(minutos: number, { comSinal = false, minutosSempre = false } = {}): string {
  const sinal = minutos < 0 ? "-" : comSinal && minutos > 0 ? "+" : "";
  const absoluto = Math.abs(Math.round(minutos));
  const horas = Math.floor(absoluto / 60);
  const resto = absoluto % 60;
  return `${sinal}${NUMERO.format(horas)}h${resto === 0 && !minutosSempre ? "" : String(resto).padStart(2, "0")}`;
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

/**
 * Data/hora de plantão (LocalDateTime, hora de parede do hospital, sem fuso):
 * "2026-10-03T19:00:00" -> "03/10 (sáb) 19:00". Lida como hora local, sem conversão.
 */
export function formatarMomento(momento: DataHoraISO): string {
  return format(parseISO(momento), "dd/MM (EEE) HH:mm", { locale: ptBR });
}

/** "2026-10-03" -> "03/10/2026". */
export function formatarDia(dia: DataISO): string {
  return format(parseISO(dia), "dd/MM/yyyy");
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
