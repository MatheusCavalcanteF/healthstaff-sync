import Link from "next/link";
import { addMonths, format, parse } from "date-fns";
import { ChevronLeft, ChevronRight } from "lucide-react";

import { rotuloCompetencia } from "@/lib/competencia";
import type { Competencia } from "@/types/api";

/** Navegação entre meses, preservando a tela atual (ex.: "/inconformidades"). */
export function SeletorCompetencia({ competencia, sufixo = "" }: { competencia: Competencia; sufixo?: string }) {
  const mes = parse(competencia, "yyyy-MM", new Date());
  const anterior = format(addMonths(mes, -1), "yyyy-MM");
  const seguinte = format(addMonths(mes, 1), "yyyy-MM");
  const botao =
    "inline-flex size-8 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-slate-900";

  return (
    <nav aria-label="Competência" className="flex items-center gap-2">
      <Link href={`/analises/${anterior}${sufixo}`} className={botao} aria-label={`Mês anterior: ${rotuloCompetencia(anterior)}`}>
        <ChevronLeft className="size-4" aria-hidden />
      </Link>
      <span className="min-w-40 text-center text-sm font-medium text-slate-700">{rotuloCompetencia(competencia)}</span>
      <Link href={`/analises/${seguinte}${sufixo}`} className={botao} aria-label={`Próximo mês: ${rotuloCompetencia(seguinte)}`}>
        <ChevronRight className="size-4" aria-hidden />
      </Link>
    </nav>
  );
}
