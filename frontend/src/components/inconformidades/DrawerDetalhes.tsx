"use client";

import { useEffect, useRef } from "react";
import { differenceInMinutes, parseISO } from "date-fns";
import { CalendarClock, MapPin, X } from "lucide-react";

import { BadgeGravidade, TagProvisorio } from "@/components/inconformidades/Badges";
import { formatarDia, formatarHoras, formatarMomento } from "@/lib/formato";
import { MEDIDA_TIPO, ROTULO_TIPO } from "@/lib/inconformidades";
import { cn } from "@/lib/utils";
import type { Inconformidade, PlantaoResumo } from "@/types/api";

interface DrawerDetalhesProps {
  inconformidade: Inconformidade | null;
  aoFechar: () => void;
}

/** Plantões envolvidos em ordem cronológica (anterior/sobreposto e o que caracteriza o alerta). */
function plantoesEmOrdem(alerta: Inconformidade): PlantaoResumo[] {
  const plantoes = [alerta.plantaoRelacionado, alerta.plantao].filter((p): p is PlantaoResumo => p !== null);
  return plantoes.sort((a, b) => (a.inicio ?? a.data).localeCompare(b.inicio ?? b.data));
}

/** Minutos entre o fim de um plantão e o início do seguinte (negativo = sobreposição). */
function intervalo(anterior: PlantaoResumo, seguinte: PlantaoResumo): number | null {
  if (!anterior.fim || !seguinte.inicio) {
    return null;
  }
  return differenceInMinutes(parseISO(seguinte.inicio), parseISO(anterior.fim));
}

/**
 * Painel lateral com o detalhe do alerta. Usa o <dialog> nativo em modo modal:
 * foco preso no painel, Esc fecha e o fundo fica inerte, sem biblioteca.
 */
export function DrawerDetalhes({ inconformidade, aoFechar }: DrawerDetalhesProps) {
  const dialogo = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const el = dialogo.current;
    if (!el) {
      return;
    }
    if (inconformidade && !el.open) {
      el.showModal();
    } else if (!inconformidade && el.open) {
      el.close();
    }
  }, [inconformidade]);

  return (
    <dialog
      ref={dialogo}
      aria-labelledby="detalhe-titulo"
      onClose={aoFechar}
      onClick={(evento) => {
        // Clique no fundo escurecido (fora do painel) fecha.
        if (evento.target === dialogo.current) {
          dialogo.current?.close();
        }
      }}
      className="fixed inset-y-0 right-0 left-auto m-0 h-full max-h-none w-full max-w-lg bg-white p-0 shadow-2xl backdrop:bg-slate-900/40"
    >
      {inconformidade && <ConteudoDetalhe alerta={inconformidade} aoFechar={() => dialogo.current?.close()} />}
    </dialog>
  );
}

function ConteudoDetalhe({ alerta, aoFechar }: { alerta: Inconformidade; aoFechar: () => void }) {
  const plantoes = plantoesEmOrdem(alerta);
  const medida = MEDIDA_TIPO[alerta.tipo];
  const ehSaldo = alerta.tipo.startsWith("SALDO") || alerta.tipo === "FOLGA_BH_SEM_SALDO";
  const formatarMedida = (minutos: number) => formatarHoras(minutos, { comSinal: ehSaldo });

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-5">
        <div>
          <div className="flex items-center gap-2">
            <BadgeGravidade gravidade={alerta.gravidade} />
            <span className="text-xs text-slate-500">{formatarDia(alerta.data)}</span>
          </div>
          <h2 id="detalhe-titulo" className="mt-2 text-lg font-semibold text-slate-900">
            {ROTULO_TIPO[alerta.tipo]}
          </h2>
          <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-600">
            {alerta.colaborador.nome}
            {alerta.colaborador.matricula && (
              <span className="font-mono text-xs text-slate-400">mat. {alerta.colaborador.matricula}</span>
            )}
            {alerta.colaborador.provisorio && <TagProvisorio />}
          </p>
        </div>
        <button
          type="button"
          onClick={aoFechar}
          className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-900"
          aria-label="Fechar detalhes"
          autoFocus
        >
          <X className="size-5" aria-hidden />
        </button>
      </header>

      <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
        <p className="text-sm leading-relaxed text-slate-700">{alerta.descricao}</p>

        {alerta.valorMinutos !== null && (
          <dl className="grid grid-cols-2 gap-3">
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <dt className="text-xs text-slate-500">{medida.valor}</dt>
              <dd className="text-lg font-semibold tabular-nums text-slate-900">
                {formatarMedida(alerta.valorMinutos)}
              </dd>
            </div>
            {alerta.limiteMinutos !== null && (
              <div className="rounded-lg bg-slate-50 px-3 py-2">
                <dt className="text-xs text-slate-500">{medida.limite}</dt>
                <dd className="text-lg font-semibold tabular-nums text-slate-900">
                  {formatarMedida(alerta.limiteMinutos)}
                </dd>
              </div>
            )}
          </dl>
        )}

        <section aria-labelledby="plantoes-titulo">
          <h3 id="plantoes-titulo" className="text-sm font-semibold text-slate-900">
            Plantões envolvidos
          </h3>
          {plantoes.length === 0 ? (
            <p className="mt-2 rounded-lg border border-dashed border-slate-300 px-4 py-3 text-sm text-slate-500">
              Este alerta compara o saldo do banco de horas com a carga do mês; não se refere a um plantão
              específico.
            </p>
          ) : (
            <ol className="mt-3">
              {plantoes.map((plantao, i) => {
                const proximo = plantoes[i + 1];
                const minutos = proximo ? intervalo(plantao, proximo) : null;
                return (
                  <li key={plantao.id}>
                    <CartaoPlantao plantao={plantao} principal={plantao.id === alerta.plantao?.id} />
                    {minutos !== null && <Intervalo minutos={minutos} alerta={alerta} />}
                  </li>
                );
              })}
            </ol>
          )}
        </section>
      </div>
    </div>
  );
}

function CartaoPlantao({ plantao, principal }: { plantao: PlantaoResumo; principal: boolean }) {
  return (
    <div
      className={cn(
        "rounded-lg border px-4 py-3",
        principal ? "border-slate-300 bg-white shadow-sm" : "border-slate-200 bg-slate-50",
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-mono text-sm font-semibold text-slate-900">{plantao.codigoOriginal}</span>
        {principal && <span className="text-[11px] font-medium text-slate-500 uppercase">gerou o alerta</span>}
      </div>
      <p className="mt-2 flex items-center gap-2 text-sm text-slate-700">
        <CalendarClock className="size-4 shrink-0 text-slate-400" aria-hidden />
        {plantao.inicio && plantao.fim ? (
          <span className="tabular-nums">
            {formatarMomento(plantao.inicio)} → {formatarMomento(plantao.fim)}
          </span>
        ) : (
          formatarDia(plantao.data)
        )}
      </p>
      <p className="mt-1 flex items-center gap-2 text-sm text-slate-500">
        <MapPin className="size-4 shrink-0 text-slate-400" aria-hidden />
        {plantao.setor}
      </p>
    </div>
  );
}

/** Faixa entre dois plantões: descanso (verde/âmbar) ou sobreposição (vermelho). */
function Intervalo({ minutos, alerta }: { minutos: number; alerta: Inconformidade }) {
  const sobreposto = minutos < 0;
  const curto = alerta.tipo === "INTERJORNADA" && alerta.limiteMinutos !== null && minutos < alerta.limiteMinutos;
  return (
    <div className="ml-6 flex items-center gap-3 border-l-2 border-dashed border-slate-300 py-3 pl-4 text-sm">
      <span
        className={cn(
          "rounded-full px-2 py-0.5 text-xs font-medium",
          sobreposto ? "bg-red-50 text-red-700" : curto ? "bg-amber-50 text-amber-800" : "bg-slate-100 text-slate-600",
        )}
      >
        {sobreposto
          ? `Sobreposição de ${formatarHoras(-minutos)}`
          : minutos === 0
            ? "Emendado (sem intervalo)"
            : `Descanso de ${formatarHoras(minutos)}`}
      </span>
      {curto && alerta.limiteMinutos !== null && (
        <span className="text-xs text-slate-500">mínimo {formatarHoras(alerta.limiteMinutos)}</span>
      )}
    </div>
  );
}
