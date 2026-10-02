import type { Gravidade, Inconformidade, TipoInconformidade } from "@/types/api";

export const GRAVIDADES: Gravidade[] = ["CRITICA", "ALTA", "MEDIA"];

export const TIPOS: TipoInconformidade[] = [
  "SOBREPOSICAO_PLANTOES",
  "INTERJORNADA",
  "JORNADA_EXCESSIVA",
  "FOLGA_BH_SEM_SALDO",
  "SALDO_POSITIVO_ESCALA_ACIMA",
  "SALDO_NEGATIVO_ESCALA_ABAIXO",
];

export const ROTULO_TIPO: Record<TipoInconformidade, string> = {
  INTERJORNADA: "Interjornada",
  SOBREPOSICAO_PLANTOES: "Sobreposição de plantões",
  JORNADA_EXCESSIVA: "Jornada excessiva",
  SALDO_POSITIVO_ESCALA_ACIMA: "Saldo alto e escala acima",
  SALDO_NEGATIVO_ESCALA_ABAIXO: "Saldo negativo e escala abaixo",
  FOLGA_BH_SEM_SALDO: "Folga BH sem saldo",
};

/** O que `valorMinutos` x `limiteMinutos` significam em cada regra. */
export const MEDIDA_TIPO: Record<TipoInconformidade, { valor: string; limite: string }> = {
  INTERJORNADA: { valor: "Descanso entre jornadas", limite: "Mínimo legal" },
  SOBREPOSICAO_PLANTOES: { valor: "Tempo sobreposto", limite: "Permitido" },
  JORNADA_EXCESSIVA: { valor: "Jornada contínua", limite: "Máximo" },
  SALDO_POSITIVO_ESCALA_ACIMA: { valor: "Saldo de banco de horas", limite: "Limite de saldo alto" },
  SALDO_NEGATIVO_ESCALA_ABAIXO: { valor: "Saldo de banco de horas", limite: "Limite de saldo baixo" },
  FOLGA_BH_SEM_SALDO: { valor: "Saldo de banco de horas", limite: "Mínimo para compensar" },
};

export function gravidadeValida(valor: unknown): valor is Gravidade {
  return typeof valor === "string" && (GRAVIDADES as string[]).includes(valor);
}

export function tipoValido(valor: unknown): valor is TipoInconformidade {
  return typeof valor === "string" && (TIPOS as string[]).includes(valor);
}

/** Sem acento, minúsculas e espaços simples: "  Vânia  " -> "vania". */
export function normalizarTexto(texto: string): string {
  return texto.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/\s+/g, " ").trim();
}

/** Busca por trecho do nome (sem acento) ou início da matrícula. */
export function filtrarPorColaborador(lista: Inconformidade[], busca: string): Inconformidade[] {
  const termo = normalizarTexto(busca);
  if (!termo) {
    return lista;
  }
  return lista.filter(
    ({ colaborador }) =>
      normalizarTexto(colaborador.nome).includes(termo) || (colaborador.matricula ?? "").startsWith(busca.trim()),
  );
}
