"use client";

import { useEffect, useState, useTransition } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { LoaderCircle, Search } from "lucide-react";

import { ROTULO_VINCULO, VINCULOS } from "@/lib/colaboradores";
import { cn } from "@/lib/utils";
import type { Vinculo } from "@/types/api";

/** Espera após a última tecla antes de consultar o backend. */
const ESPERA_BUSCA_MS = 400;

interface FiltrosColaboradoresProps {
  busca: string;
  vinculo?: Vinculo;
  /** A tabela renderizada no servidor; fica esmaecida enquanto a nova página carrega. */
  children: React.ReactNode;
}

/**
 * Busca e vínculo são filtros do backend (lista paginada no servidor): mudar
 * qualquer um navega para a nova URL, que volta para a primeira página.
 */
export function FiltrosColaboradores({ busca, vinculo, children }: FiltrosColaboradoresProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [carregando, iniciarTransicao] = useTransition();

  // `texto` é o que está no campo; `enviada` é a última busca levada à URL.
  // Se a URL mudar por fora (voltar do navegador) para algo que não enviamos,
  // o campo acompanha. Enquanto a navegação que nós disparamos não termina,
  // `busca` ainda é a antiga: comparar com `enviada` evita apagar o que foi digitado.
  const [texto, setTexto] = useState(busca);
  const [enviada, setEnviada] = useState(busca);
  const [buscaAnterior, setBuscaAnterior] = useState(busca);
  if (busca !== buscaAnterior) {
    setBuscaAnterior(busca);
    if (busca !== enviada) {
      setEnviada(busca);
      setTexto(busca);
    }
  }

  function navegar(mudancas: Record<string, string | null>, { substituir = false } = {}) {
    const params = new URLSearchParams(searchParams.toString());
    for (const [chave, valor] of Object.entries({ ...mudancas, pagina: null })) {
      if (valor) {
        params.set(chave, valor);
      } else {
        params.delete(chave);
      }
    }
    const consulta = params.toString();
    const url = consulta ? `${pathname}?${consulta}` : pathname;
    iniciarTransicao(() => {
      // Digitação substitui a entrada do histórico; não empilha uma por palavra.
      if (substituir) {
        router.replace(url, { scroll: false });
      } else {
        router.push(url, { scroll: false });
      }
    });
  }

  function buscar(valor: string, opcoes?: { substituir?: boolean }) {
    const termo = valor.trim();
    if (termo === enviada) {
      return;
    }
    setEnviada(termo);
    navegar({ busca: termo || null }, opcoes);
  }

  // Debounce: só consulta quando a pessoa para de digitar.
  useEffect(() => {
    if (texto.trim() === enviada) {
      return;
    }
    const espera = setTimeout(() => buscar(texto, { substituir: true }), ESPERA_BUSCA_MS);
    return () => clearTimeout(espera);
  });

  return (
    <>
      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-white p-4">
        <form
          role="search"
          className="flex min-w-56 flex-1 flex-col gap-1"
          onSubmit={(e) => {
            e.preventDefault();
            buscar(texto);
          }}
        >
          <label htmlFor="busca-colaborador" className="text-xs font-medium text-slate-500">
            Colaborador
          </label>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-slate-400" aria-hidden />
            <input
              id="busca-colaborador"
              type="search"
              value={texto}
              onChange={(e) => setTexto(e.target.value)}
              placeholder="Nome ou matrícula"
              autoComplete="off"
              className="h-9 w-full rounded-md border border-slate-300 bg-white pr-8 pl-8 text-sm text-slate-900 shadow-xs focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 focus:outline-none"
            />
            {carregando && (
              <LoaderCircle
                className="absolute top-1/2 right-2.5 size-4 -translate-y-1/2 animate-spin text-slate-400"
                aria-label="Carregando"
              />
            )}
          </div>
        </form>

        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-xs font-medium text-slate-500">Vínculo</legend>
          <div className="inline-flex h-9 rounded-md border border-slate-300 bg-slate-50 p-0.5">
            {[undefined, ...VINCULOS].map((opcao) => {
              const ativo = opcao === vinculo;
              return (
                <button
                  key={opcao ?? "TODOS"}
                  type="button"
                  aria-pressed={ativo}
                  onClick={() => !ativo && navegar({ vinculo: opcao ?? null })}
                  className={cn(
                    "rounded px-3 text-sm font-medium transition-colors",
                    ativo ? "bg-white text-slate-900 shadow-sm" : "text-slate-500 hover:text-slate-900",
                  )}
                >
                  {opcao ? ROTULO_VINCULO[opcao] : "Todos"}
                </button>
              );
            })}
          </div>
        </fieldset>
      </div>

      <div className={cn("mt-4 transition-opacity", carregando && "opacity-60")} aria-busy={carregando}>
        {children}
      </div>
    </>
  );
}
