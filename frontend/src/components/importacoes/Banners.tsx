"use client";

import Link from "next/link";
import { ArrowRight, CircleCheck, Info } from "lucide-react";

import { Banner } from "@/components/estado/Banner";
import { rotuloCompetencia } from "@/lib/competencia";
import { formatarDataHora, formatarDia, formatarNumero } from "@/lib/formato";
import type { Competencia, ImportacaoResponse } from "@/types/api";

/** "2026-10-01" -> "2026-10". */
function competenciaDe(data: string): Competencia {
  return data.slice(0, 7) as Competencia;
}

export function BannerSucesso({ resposta, aoFechar }: { resposta: ImportacaoResponse; aoFechar: () => void }) {
  const ehEscala = resposta.escalas.length > 0 || resposta.tipoDocumento.startsWith("ESCALA");
  const avisos = resposta.inconsistencias.filter((i) => i.severidade !== "INFO");
  const competencia = competenciaDe(resposta.escalas[0]?.competencia ?? resposta.periodoInicio);

  if (resposta.duplicada) {
    return (
      <Banner tom="azul" icone={Info} titulo="Este arquivo já tinha sido importado" aoFechar={aoFechar}>
        <p>
          <strong className="font-medium">{resposta.nomeArquivo}</strong> é idêntico a uma importação de{" "}
          {formatarDataHora(resposta.importadoEm)}. Nada foi alterado.
        </p>
      </Banner>
    );
  }

  return (
    <Banner tom="verde" icone={CircleCheck} titulo="Importação concluída" aoFechar={aoFechar}>
      <p>
        <strong className="font-medium">{resposta.nomeArquivo}</strong> · período de {formatarDia(resposta.periodoInicio)}{" "}
        a {formatarDia(resposta.periodoFim)}
      </p>
      <ul className="list-inside list-disc">
        <li>{formatarNumero(resposta.totalColaboradores)} colaboradores</li>
        {ehEscala ? (
          <>
            <li>{formatarNumero(resposta.totalPlantoes)} plantões</li>
            {resposta.escalas.map((e) => (
              <li key={`${e.setor}-${e.versao}`}>
                {e.setor}: versão {e.versao}
                {e.vigente ? " (vigente)" : ""}
              </li>
            ))}
          </>
        ) : (
          <li>{formatarNumero(resposta.saldos)} saldos de banco de horas</li>
        )}
      </ul>
      {avisos.length > 0 && (
        <details className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900">
          <summary className="cursor-pointer font-medium">
            {avisos.length === 1 ? "1 ponto para conferir" : `${formatarNumero(avisos.length)} pontos para conferir`}
          </summary>
          <ul className="mt-2 max-h-48 space-y-1 overflow-y-auto text-xs">
            {avisos.map((a, i) => (
              <li key={i}>
                <span className="font-semibold">{a.severidade === "ERRO" ? "Erro" : "Aviso"}:</span> {a.mensagem}
              </li>
            ))}
          </ul>
        </details>
      )}
      <Link
        href={`/analises/${competencia}`}
        className="inline-flex items-center gap-1 font-medium text-emerald-800 underline-offset-2 hover:underline"
      >
        Ver análise de {rotuloCompetencia(competencia)}
        <ArrowRight className="size-4" aria-hidden />
      </Link>
    </Banner>
  );
}
