import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { CalendarX2, FileUp } from "lucide-react";

import { SeletorCompetencia } from "@/components/dashboard/SeletorCompetencia";
import { ErroCarregamento } from "@/components/estado/ErroCarregamento";
import { EstadoVazio } from "@/components/estado/EstadoVazio";
import { PainelInconformidades } from "@/components/inconformidades/PainelInconformidades";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { ApiError } from "@/lib/api/client";
import { listarInconformidades } from "@/lib/api/endpoints";
import { competenciaValida, rotuloCompetencia } from "@/lib/competencia";
import { gravidadeValida, tipoValido } from "@/lib/inconformidades";
import type { Competencia, FiltrosInconformidade, Inconformidade } from "@/types/api";

export async function generateMetadata(
  props: PageProps<"/analises/[competencia]/inconformidades">,
): Promise<Metadata> {
  const { competencia } = await props.params;
  return {
    title: competenciaValida(competencia) ? `Inconformidades — ${rotuloCompetencia(competencia)}` : "Inconformidades",
  };
}

type Carregamento =
  | { estado: "ok"; inconformidades: Inconformidade[] }
  | { estado: "sem-analise" }
  | { estado: "erro"; erro: ApiError };

async function carregar(competencia: Competencia, filtros: FiltrosInconformidade): Promise<Carregamento> {
  try {
    return { estado: "ok", inconformidades: await listarInconformidades(competencia, filtros) };
  } catch (erro) {
    if (erro instanceof ApiError && erro.codigo === "ANALISE_NAO_ENCONTRADA") {
      return { estado: "sem-analise" };
    }
    if (erro instanceof ApiError) {
      return { estado: "erro", erro };
    }
    throw erro;
  }
}

/** Alertas do mês (interjornada, sobreposição, carga horária e banco de horas). */
export default async function Inconformidades(props: PageProps<"/analises/[competencia]/inconformidades">) {
  const { competencia } = await props.params;
  if (!competenciaValida(competencia)) {
    notFound();
  }

  // Valores inválidos na URL são ignorados (lista sem aquele filtro), não erro.
  const { gravidade, tipo } = await props.searchParams;
  const filtros: FiltrosInconformidade = {
    gravidade: gravidadeValida(gravidade) ? gravidade : undefined,
    tipo: tipoValido(tipo) ? tipo : undefined,
  };

  const resultado = await carregar(competencia, filtros);

  return (
    <>
      <CabecalhoPagina
        titulo="Inconformidades"
        subtitulo="Violações de interjornada, jornada e banco de horas"
      >
        <SeletorCompetencia competencia={competencia} sufixo="/inconformidades" />
      </CabecalhoPagina>

      {resultado.estado === "sem-analise" && (
        <EstadoVazio
          icone={CalendarX2}
          titulo="Nenhuma análise gerada para este mês"
          descricao={`Ainda não há escala importada para ${rotuloCompetencia(competencia)}. Importe a escala do setor (e o relatório de Banco de Horas do Two) para gerar a análise.`}
          acao={{ href: "/importacoes", rotulo: "Ir para Importações", icone: FileUp }}
        />
      )}

      {resultado.estado === "erro" && <ErroCarregamento erro={resultado.erro} oQue="as inconformidades do mês" />}

      {resultado.estado === "ok" && (
        <PainelInconformidades
          inconformidades={resultado.inconformidades}
          gravidade={filtros.gravidade}
          tipo={filtros.tipo}
        />
      )}
    </>
  );
}
