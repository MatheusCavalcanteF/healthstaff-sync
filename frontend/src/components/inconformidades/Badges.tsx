import { ROTULO_GRAVIDADE } from "@/lib/formato";
import { cn } from "@/lib/utils";
import type { Gravidade } from "@/types/api";

const COR: Record<Gravidade, string> = {
  CRITICA: "bg-red-50 text-red-700 ring-red-600/20",
  ALTA: "bg-orange-50 text-orange-700 ring-orange-600/20",
  MEDIA: "bg-yellow-50 text-yellow-800 ring-yellow-600/30",
};

const PONTO: Record<Gravidade, string> = {
  CRITICA: "bg-red-500",
  ALTA: "bg-orange-500",
  MEDIA: "bg-yellow-500",
};

export function BadgeGravidade({ gravidade }: { gravidade: Gravidade }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
        COR[gravidade],
      )}
    >
      <span className={cn("size-1.5 rounded-full", PONTO[gravidade])} aria-hidden />
      {ROTULO_GRAVIDADE[gravidade]}
    </span>
  );
}

/** Colaborador só na escala, sem matrícula do Two. */
export function TagProvisorio() {
  return (
    <span
      title="Está na escala mas não no sistema de ponto (Two): sem matrícula oficial"
      className="inline-flex items-center rounded border border-dashed border-violet-300 bg-violet-50 px-1.5 py-px text-[11px] font-medium text-violet-700"
    >
      Provisório
    </span>
  );
}
