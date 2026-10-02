import Link from "next/link";
import { ChevronLeft, ChevronRight } from "lucide-react";

import { ROTULO_CATEGORIA, ROTULO_VINCULO } from "@/lib/colaboradores";
import { formatarDia, formatarHoras, formatarNumero } from "@/lib/formato";
import { cn } from "@/lib/utils";
import type { Colaborador, Pagina, SaldoBancoHoras, Vinculo } from "@/types/api";

const COR_VINCULO: Record<Vinculo, string> = {
  OFICIAL: "bg-emerald-50 text-emerald-700 ring-emerald-600/20",
  PROVISORIO: "bg-amber-50 text-amber-800 ring-amber-600/30",
};

interface TabelaColaboradoresProps {
  pagina: Pagina<Colaborador>;
  /** Monta o link de outra página mantendo os filtros atuais. */
  hrefPagina: (pagina: number) => string;
}

export function TabelaColaboradores({ pagina, hrefPagina }: TabelaColaboradoresProps) {
  const inicio = pagina.pagina * pagina.tamanho + 1;
  const fim = pagina.pagina * pagina.tamanho + pagina.conteudo.length;

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[56rem] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs font-medium tracking-wide text-slate-500 uppercase">
            <tr>
              <th scope="col" className="px-4 py-3">Nome</th>
              <th scope="col" className="px-4 py-3">Vínculo</th>
              <th scope="col" className="px-4 py-3">Cargo</th>
              <th scope="col" className="px-4 py-3">Categoria</th>
              <th scope="col" className="px-4 py-3">COREN</th>
              <th scope="col" className="px-4 py-3 text-right">Banco de horas</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {pagina.conteudo.map((c) => (
              <tr key={c.id} className="align-top hover:bg-slate-50">
                <td className="px-4 py-3">
                  <div className="font-medium text-slate-900">{c.nome}</div>
                  <div className="font-mono text-xs text-slate-400">{c.matricula ?? "sem matrícula"}</div>
                </td>
                <td className="px-4 py-3">
                  <span
                    title={
                      c.vinculo === "PROVISORIO"
                        ? "Está na escala mas não no sistema de ponto (Two): sem matrícula oficial"
                        : undefined
                    }
                    className={cn(
                      "inline-flex rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                      COR_VINCULO[c.vinculo],
                    )}
                  >
                    {ROTULO_VINCULO[c.vinculo]}
                  </span>
                </td>
                <td className="px-4 py-3 text-slate-600">
                  {c.cargo ?? <Vazio />}
                  {c.jornada && <div className="text-xs text-slate-400">{c.jornada}</div>}
                </td>
                <td className="px-4 py-3 whitespace-nowrap text-slate-600">
                  {c.categoria ? ROTULO_CATEGORIA[c.categoria] : <Vazio titulo="Cargo desconhecido" />}
                </td>
                <td className="px-4 py-3 font-mono text-xs whitespace-nowrap text-slate-600">
                  {c.registroConselho ?? <Vazio />}
                </td>
                <td className="px-4 py-3 text-right">
                  <Saldo saldo={c.saldoBancoHoras} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <nav
        aria-label="Paginação"
        className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-4 py-3 text-sm text-slate-600"
      >
        <span>
          {formatarNumero(inicio)}–{formatarNumero(fim)} de {formatarNumero(pagina.totalElementos)}{" "}
          {pagina.totalElementos === 1 ? "colaborador" : "colaboradores"}
        </span>
        <div className="flex items-center gap-2">
          <LinkPagina href={pagina.pagina > 0 ? hrefPagina(pagina.pagina - 1) : null}>
            <ChevronLeft className="size-4" aria-hidden />
            Anterior
          </LinkPagina>
          <span className="px-1 tabular-nums">
            Página {formatarNumero(pagina.pagina + 1)} de {formatarNumero(pagina.totalPaginas)}
          </span>
          <LinkPagina href={pagina.pagina + 1 < pagina.totalPaginas ? hrefPagina(pagina.pagina + 1) : null}>
            Próxima
            <ChevronRight className="size-4" aria-hidden />
          </LinkPagina>
        </div>
      </nav>
    </div>
  );
}

function Vazio({ titulo }: { titulo?: string }) {
  return (
    <span className="text-slate-300" title={titulo}>
      —
    </span>
  );
}

/** Saldo do relatório mais recente do Two: verde positivo, vermelho negativo. */
function Saldo({ saldo }: { saldo: SaldoBancoHoras | null }) {
  if (!saldo) {
    return <span className="text-xs text-slate-400">Sem saldo</span>;
  }
  return (
    <span title={`Relatório do Two de ${formatarDia(saldo.dataReferencia)}`}>
      <span
        className={cn(
          "font-semibold tabular-nums",
          saldo.minutos > 0 && "text-emerald-700",
          saldo.minutos < 0 && "text-red-700",
          saldo.minutos === 0 && "text-slate-600",
        )}
      >
        {formatarHoras(saldo.minutos, { comSinal: true, minutosSempre: true })}
      </span>
      <span className="block text-xs text-slate-400">em {formatarDia(saldo.dataReferencia)}</span>
    </span>
  );
}

function LinkPagina({ href, children }: { href: string | null; children: React.ReactNode }) {
  const classe = "inline-flex items-center gap-1 rounded-md border px-2.5 py-1.5 font-medium";
  if (!href) {
    return (
      <span aria-disabled className={cn(classe, "border-slate-200 text-slate-300")}>
        {children}
      </span>
    );
  }
  return (
    // Sem prefetch: cada página é uma consulta ao backend.
    <Link
      href={href}
      prefetch={false}
      scroll={false}
      className={cn(classe, "border-slate-300 text-slate-700 hover:bg-slate-50")}
    >
      {children}
    </Link>
  );
}
