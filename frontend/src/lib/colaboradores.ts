import type { CategoriaProfissional, Vinculo } from "@/types/api";

/** Tamanho fixo da página na tela de Colaboradores (o backend aceita até 200). */
export const TAMANHO_PAGINA = 50;

export const VINCULOS: Vinculo[] = ["OFICIAL", "PROVISORIO"];

export const ROTULO_VINCULO: Record<Vinculo, string> = {
  OFICIAL: "Oficial",
  PROVISORIO: "Provisório",
};

export const ROTULO_CATEGORIA: Record<CategoriaProfissional, string> = {
  ENFERMEIRO: "Enfermeiro(a)",
  TECNICO_ENFERMAGEM: "Técnico(a) de enfermagem",
  ADMINISTRATIVO: "Administrativo",
  OUTRO: "Outro",
};

export function vinculoValido(valor: unknown): valor is Vinculo {
  return typeof valor === "string" && (VINCULOS as string[]).includes(valor);
}

/** `pagina` da URL (começa em 0, como na API); ausente ou inválida = 0. */
export function paginaDaUrl(valor: unknown): number {
  const numero = typeof valor === "string" ? Number(valor) : Number.NaN;
  return Number.isInteger(numero) && numero > 0 ? numero : 0;
}

/** Primeiro valor de um parâmetro de busca (`?busca=a&busca=b` vira "a"). */
export function primeiro(valor: string | string[] | undefined): string | undefined {
  return Array.isArray(valor) ? valor[0] : valor;
}
