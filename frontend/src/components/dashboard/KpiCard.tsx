import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

interface KpiCardProps {
  titulo: string;
  icone: LucideIcon;
  /** Número principal, já formatado. */
  valor: string;
  /** Linha de contexto abaixo do valor. */
  descricao?: string;
  /** Destaque de cor do ícone. */
  tom?: "neutro" | "alerta" | "sucesso";
  children?: ReactNode;
}

const TONS = {
  neutro: "bg-slate-100 text-slate-600",
  alerta: "bg-amber-50 text-amber-600",
  sucesso: "bg-emerald-50 text-emerald-600",
};

export function KpiCard({ titulo, icone: Icone, valor, descricao, tom = "neutro", children }: KpiCardProps) {
  return (
    <article className="flex flex-col rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <header className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-medium text-slate-500">{titulo}</h2>
        <span className={cn("rounded-md p-1.5", TONS[tom])}>
          <Icone className="size-4" aria-hidden />
        </span>
      </header>
      <p className="mt-3 text-3xl font-semibold tracking-tight text-slate-900 tabular-nums">{valor}</p>
      {descricao && <p className="mt-1 text-sm text-slate-500">{descricao}</p>}
      {children && <div className="mt-4 border-t border-slate-100 pt-4">{children}</div>}
    </article>
  );
}
