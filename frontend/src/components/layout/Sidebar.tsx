"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { HeartPulse, LayoutDashboard, TriangleAlert, Upload, Users, type LucideIcon } from "lucide-react";

import { competenciaDaRota, rotuloCompetencia } from "@/lib/competencia";
import { cn } from "@/lib/utils";

interface ItemMenu {
  href: string;
  rotulo: string;
  icone: LucideIcon;
  ativo: (caminho: string) => boolean;
}

/**
 * Dashboard e Inconformidades seguem a competência que está na URL (quem
 * está vendo 2026-09 continua em 2026-09). Fora das análises, apontam para
 * /analises, que redireciona para o mês corrente na hora da requisição.
 */
function itensDoMenu(competencia: string | null): ItemMenu[] {
  return [
    {
      href: "/importacoes",
      rotulo: "Importações",
      icone: Upload,
      ativo: (c) => c.startsWith("/importacoes"),
    },
    {
      href: competencia ? `/analises/${competencia}` : "/analises",
      rotulo: "Dashboard",
      icone: LayoutDashboard,
      ativo: (c) => /^\/analises(\/[^/]+)?\/?$/.test(c),
    },
    {
      href: competencia ? `/analises/${competencia}/inconformidades` : "/analises?para=inconformidades",
      rotulo: "Inconformidades",
      icone: TriangleAlert,
      ativo: (c) => /^\/analises\/[^/]+\/inconformidades/.test(c),
    },
    {
      href: "/colaboradores",
      rotulo: "Colaboradores",
      icone: Users,
      ativo: (c) => c.startsWith("/colaboradores"),
    },
  ];
}

export function Sidebar() {
  const caminho = usePathname();
  const competencia = competenciaDaRota(caminho);

  return (
    <aside className="flex shrink-0 flex-col border-b border-slate-200 bg-white md:h-screen md:w-64 md:border-r md:border-b-0">
      <div className="flex items-center gap-2 px-5 py-4">
        <HeartPulse className="size-6 text-teal-600" aria-hidden />
        <div className="leading-tight">
          <p className="font-semibold text-slate-900">HealthStaff Sync</p>
          <p className="text-xs text-slate-500">Escalas e banco de horas</p>
        </div>
      </div>

      <nav aria-label="Principal" className="flex gap-1 overflow-x-auto px-3 pb-3 md:flex-col md:pb-0">
        {itensDoMenu(competencia).map(({ href, rotulo, icone: Icone, ativo }) => {
          const selecionado = ativo(caminho);
          return (
            <Link
              key={rotulo}
              href={href}
              aria-current={selecionado ? "page" : undefined}
              className={cn(
                "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors",
                selecionado
                  ? "bg-teal-50 text-teal-700"
                  : "text-slate-600 hover:bg-slate-100 hover:text-slate-900",
              )}
            >
              <Icone className="size-4 shrink-0" aria-hidden />
              {rotulo}
            </Link>
          );
        })}
      </nav>

      {competencia && (
        <p className="mt-auto hidden border-t border-slate-200 px-5 py-4 text-xs text-slate-500 md:block">
          Competência: <span className="font-medium text-slate-700">{rotuloCompetencia(competencia)}</span>
        </p>
      )}
    </aside>
  );
}
