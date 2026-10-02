import Link from "next/link";
import type { LucideIcon } from "lucide-react";

interface EstadoVazioProps {
  icone: LucideIcon;
  titulo: string;
  descricao: string;
  acao?: { href: string; rotulo: string; icone?: LucideIcon };
}

/** Tela sem dados (ex.: mês sem análise), com uma ação para resolver. */
export function EstadoVazio({ icone: Icone, titulo, descricao, acao }: EstadoVazioProps) {
  const IconeAcao = acao?.icone;
  return (
    <section
      aria-labelledby="estado-vazio-titulo"
      className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-16 text-center"
    >
      <div className="rounded-full bg-teal-50 p-3">
        <Icone className="size-7 text-teal-600" aria-hidden />
      </div>
      <h2 id="estado-vazio-titulo" className="mt-4 text-lg font-semibold text-slate-900">
        {titulo}
      </h2>
      <p className="mt-2 max-w-md text-sm text-slate-500">{descricao}</p>
      {acao && (
        <Link
          href={acao.href}
          className="mt-6 inline-flex items-center gap-2 rounded-md bg-teal-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-600"
        >
          {IconeAcao && <IconeAcao className="size-4" aria-hidden />}
          {acao.rotulo}
        </Link>
      )}
    </section>
  );
}
