import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { competenciaValida, rotuloCompetencia } from "@/lib/competencia";

export async function generateMetadata(
  props: PageProps<"/analises/[competencia]/inconformidades">,
): Promise<Metadata> {
  const { competencia } = await props.params;
  return {
    title: competenciaValida(competencia) ? `Inconformidades — ${rotuloCompetencia(competencia)}` : "Inconformidades",
  };
}

/** Alertas do mês (interjornada, sobreposição, carga horária e banco de horas). */
export default async function Inconformidades(props: PageProps<"/analises/[competencia]/inconformidades">) {
  const { competencia } = await props.params;
  if (!competenciaValida(competencia)) {
    notFound();
  }

  return (
    <CabecalhoPagina
      titulo="Inconformidades"
      subtitulo={`${rotuloCompetencia(competencia)} · violações de interjornada, jornada e banco de horas`}
    />
  );
}
