import type { ReactNode } from "react";

interface CabecalhoPaginaProps {
  titulo: string;
  subtitulo?: string;
  /** Ações à direita (botões, filtros). */
  children?: ReactNode;
}

export function CabecalhoPagina({ titulo, subtitulo, children }: CabecalhoPaginaProps) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 pb-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{titulo}</h1>
        {subtitulo && <p className="mt-1 text-sm text-slate-500">{subtitulo}</p>}
      </div>
      {children}
    </header>
  );
}
