"use client";

import { useState, useTransition } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ChevronLeft, ChevronRight, CircleCheck, FilterX, Search } from "lucide-react";

import { BadgeGravidade, TagProvisorio } from "@/components/inconformidades/Badges";
import { DrawerDetalhes } from "@/components/inconformidades/DrawerDetalhes";
import { ROTULO_GRAVIDADE, formatarDia, formatarNumero } from "@/lib/formato";
import { GRAVIDADES, ROTULO_TIPO, TIPOS, filtrarPorColaborador } from "@/lib/inconformidades";
import { cn } from "@/lib/utils";
import type { Gravidade, Inconformidade, TipoInconformidade } from "@/types/api";

const POR_PAGINA = 25;

interface PainelInconformidadesProps {
  /** Lista já filtrada por gravidade/tipo no backend. */
  inconformidades: Inconformidade[];
  gravidade?: Gravidade;
  tipo?: TipoInconformidade;
}

/**
 * Filtros, tabela e painel de detalhe. Gravidade e tipo são filtros do backend
 * (navegação no servidor); busca, página e alerta aberto ficam só na URL do
 * navegador (history.replaceState), sem nova requisição: a lista do mês já está aqui.
 */
export function PainelInconformidades({ inconformidades, gravidade, tipo }: PainelInconformidadesProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [carregando, iniciarTransicao] = useTransition();

  // Texto local para o campo responder na hora; ressincroniza quando a URL muda
  // por fora (voltar do navegador, "limpar filtros").
  const buscaUrl = searchParams.get("busca") ?? "";
  const [busca, setBusca] = useState(buscaUrl);
  const [buscaSincronizada, setBuscaSincronizada] = useState(buscaUrl);
  if (buscaUrl !== buscaSincronizada) {
    setBuscaSincronizada(buscaUrl);
    setBusca(buscaUrl);
  }

  function urlCom(mudancas: Record<string, string | null>): string {
    const params = new URLSearchParams(searchParams.toString());
    for (const [chave, valor] of Object.entries(mudancas)) {
      if (valor) {
        params.set(chave, valor);
      } else {
        params.delete(chave);
      }
    }
    const consulta = params.toString();
    return consulta ? `${pathname}?${consulta}` : pathname;
  }

  /** Muda filtro do backend: a página do servidor busca de novo. */
  function navegar(mudancas: Record<string, string | null>) {
    iniciarTransicao(() => {
      router.push(urlCom({ ...mudancas, pagina: null, alerta: null }), { scroll: false });
    });
  }

  /** Estado só de tela: atualiza a URL (compartilhável) sem ir ao servidor. */
  function atualizarLocal(mudancas: Record<string, string | null>) {
    window.history.replaceState(null, "", urlCom(mudancas));
  }

  const filtradas = filtrarPorColaborador(inconformidades, busca);
  const totalPaginas = Math.max(1, Math.ceil(filtradas.length / POR_PAGINA));
  const paginaUrl = Number.parseInt(searchParams.get("pagina") ?? "1", 10);
  const pagina = Number.isFinite(paginaUrl) ? Math.min(Math.max(paginaUrl, 1), totalPaginas) : 1;
  const visiveis = filtradas.slice((pagina - 1) * POR_PAGINA, pagina * POR_PAGINA);

  const alertaId = Number(searchParams.get("alerta"));
  const aberto = inconformidades.find((i) => i.id === alertaId) ?? null;

  const algumFiltro = Boolean(gravidade || tipo || busca.trim());

  return (
    <>
      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-white p-4">
        <Campo rotulo="Gravidade" id="filtro-gravidade">
          <select
            id="filtro-gravidade"
            value={gravidade ?? ""}
            onChange={(e) => navegar({ gravidade: e.target.value || null })}
            className={CLASSE_CONTROLE}
          >
            <option value="">Todas</option>
            {GRAVIDADES.map((g) => (
              <option key={g} value={g}>
                {ROTULO_GRAVIDADE[g]}
              </option>
            ))}
          </select>
        </Campo>

        <Campo rotulo="Regra" id="filtro-tipo">
          <select
            id="filtro-tipo"
            value={tipo ?? ""}
            onChange={(e) => navegar({ tipo: e.target.value || null })}
            className={CLASSE_CONTROLE}
          >
            <option value="">Todas</option>
            {TIPOS.map((t) => (
              <option key={t} value={t}>
                {ROTULO_TIPO[t]}
              </option>
            ))}
          </select>
        </Campo>

        <Campo rotulo="Colaborador" id="filtro-busca" className="min-w-56 flex-1">
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-slate-400" aria-hidden />
            <input
              id="filtro-busca"
              type="search"
              value={busca}
              placeholder="Nome ou matrícula"
              autoComplete="off"
              onChange={(e) => {
                setBusca(e.target.value);
                atualizarLocal({ busca: e.target.value.trim() ? e.target.value : null, pagina: null });
              }}
              className={cn(CLASSE_CONTROLE, "w-full pl-8")}
            />
          </div>
        </Campo>

        {algumFiltro && (
          <button
            type="button"
            onClick={() => {
              setBusca("");
              navegar({ gravidade: null, tipo: null, busca: null });
            }}
            className="inline-flex h-9 items-center gap-1.5 rounded-md px-3 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900"
          >
            <FilterX className="size-4" aria-hidden />
            Limpar filtros
          </button>
        )}
      </div>

      <p className="mt-4 mb-2 text-sm text-slate-500" aria-live="polite">
        {carregando
          ? "Atualizando…"
          : `${formatarNumero(filtradas.length)} ${filtradas.length === 1 ? "alerta" : "alertas"}`}
      </p>

      {filtradas.length === 0 ? (
        <SemResultados algumFiltro={algumFiltro} />
      ) : (
        <div
          className={cn(
            "overflow-x-auto rounded-xl border border-slate-200 bg-white transition-opacity",
            carregando && "opacity-60",
          )}
        >
          <table className="w-full min-w-[48rem] text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs font-medium tracking-wide text-slate-500 uppercase">
              <tr>
                <th scope="col" className="px-4 py-3">Data</th>
                <th scope="col" className="px-4 py-3">Gravidade</th>
                <th scope="col" className="px-4 py-3">Colaborador</th>
                <th scope="col" className="px-4 py-3">Regra</th>
                <th scope="col" className="px-4 py-3">Descrição</th>
                <th scope="col" className="px-4 py-3">
                  <span className="sr-only">Ações</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {visiveis.map((alerta) => (
                <tr
                  key={alerta.id}
                  onClick={() => atualizarLocal({ alerta: String(alerta.id) })}
                  className={cn(
                    "cursor-pointer align-top hover:bg-slate-50",
                    alerta.id === aberto?.id && "bg-teal-50/60 hover:bg-teal-50/60",
                  )}
                >
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums text-slate-600">{formatarDia(alerta.data)}</td>
                  <td className="px-4 py-3">
                    <BadgeGravidade gravidade={alerta.gravidade} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="font-medium text-slate-900">{alerta.colaborador.nome}</span>
                      {alerta.colaborador.provisorio && <TagProvisorio />}
                    </div>
                    {alerta.colaborador.matricula && (
                      <span className="font-mono text-xs text-slate-400">{alerta.colaborador.matricula}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-slate-600">{ROTULO_TIPO[alerta.tipo]}</td>
                  <td className="max-w-md px-4 py-3 text-slate-600">
                    <span className="line-clamp-2">{alerta.descricao}</span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {/* A linha inteira é clicável; o botão dá acesso pelo teclado. */}
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        atualizarLocal({ alerta: String(alerta.id) });
                      }}
                      className="rounded-md px-2 py-1 text-xs font-medium text-teal-700 hover:bg-teal-50"
                    >
                      Detalhes
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {totalPaginas > 1 && (
            <nav
              aria-label="Paginação"
              className="flex items-center justify-between border-t border-slate-200 px-4 py-3 text-sm text-slate-600"
            >
              <span>
                {formatarNumero((pagina - 1) * POR_PAGINA + 1)}–
                {formatarNumero(Math.min(pagina * POR_PAGINA, filtradas.length))} de {formatarNumero(filtradas.length)}
              </span>
              <div className="flex items-center gap-1">
                <BotaoPagina
                  rotulo="Página anterior"
                  desabilitado={pagina <= 1}
                  aoClicar={() => atualizarLocal({ pagina: pagina - 1 > 1 ? String(pagina - 1) : null })}
                >
                  <ChevronLeft className="size-4" aria-hidden />
                </BotaoPagina>
                <span className="px-2 tabular-nums">
                  {pagina} / {totalPaginas}
                </span>
                <BotaoPagina
                  rotulo="Próxima página"
                  desabilitado={pagina >= totalPaginas}
                  aoClicar={() => atualizarLocal({ pagina: String(pagina + 1) })}
                >
                  <ChevronRight className="size-4" aria-hidden />
                </BotaoPagina>
              </div>
            </nav>
          )}
        </div>
      )}

      <DrawerDetalhes inconformidade={aberto} aoFechar={() => atualizarLocal({ alerta: null })} />
    </>
  );
}

const CLASSE_CONTROLE =
  "h-9 rounded-md border border-slate-300 bg-white px-2.5 text-sm text-slate-900 shadow-xs focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 focus:outline-none";

function Campo({
  rotulo,
  id,
  className,
  children,
}: {
  rotulo: string;
  id: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-1", className)}>
      <label htmlFor={id} className="text-xs font-medium text-slate-500">
        {rotulo}
      </label>
      {children}
    </div>
  );
}

function BotaoPagina({
  rotulo,
  desabilitado,
  aoClicar,
  children,
}: {
  rotulo: string;
  desabilitado: boolean;
  aoClicar: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={rotulo}
      disabled={desabilitado}
      onClick={aoClicar}
      className="rounded-md p-1.5 hover:bg-slate-100 disabled:pointer-events-none disabled:opacity-40"
    >
      {children}
    </button>
  );
}

function SemResultados({ algumFiltro }: { algumFiltro: boolean }) {
  if (algumFiltro) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
        <p className="font-medium text-slate-900">Nenhum alerta com esses filtros</p>
        <p className="mt-1 text-sm text-slate-500">Ajuste a gravidade, a regra ou o nome buscado.</p>
      </div>
    );
  }
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <CircleCheck className="size-8 text-teal-600" aria-hidden />
      <p className="mt-3 font-medium text-slate-900">Nenhuma inconformidade neste mês</p>
      <p className="mt-1 text-sm text-slate-500">A escala respeita interjornada, jornada e banco de horas.</p>
    </div>
  );
}
