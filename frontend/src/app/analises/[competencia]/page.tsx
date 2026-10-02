import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { competenciaValida, rotuloCompetencia } from "@/lib/competencia";

export async function generateMetadata(props: PageProps<"/analises/[competencia]">): Promise<Metadata> {
  const { competencia } = await props.params;
  return { title: competenciaValida(competencia) ? `Dashboard — ${rotuloCompetencia(competencia)}` : "Dashboard" };
}

/** Painel do mês: métricas gerais, alertas por gravidade e resumo de horas. */
export default async function Dashboard(props: PageProps<"/analises/[competencia]">) {
  const { competencia } = await props.params;
  if (!competenciaValida(competencia)) {
    notFound();
  }

  return (
    <CabecalhoPagina
      titulo="Dashboard"
      subtitulo={`${rotuloCompetencia(competencia)} · métricas gerais, alertas e resumo de horas`}
    />
  );
}
