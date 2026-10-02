import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { CalendarX2, FileUp } from "lucide-react";

import {
  CardBancoHoras,
  CardColaboradoresAfetados,
  CardDistribuicaoCarga,
  CardInconformidades,
} from "@/components/dashboard/CardsDashboard";
import { SeletorCompetencia } from "@/components/dashboard/SeletorCompetencia";
import { ErroCarregamento } from "@/components/estado/ErroCarregamento";
import { EstadoVazio } from "@/components/estado/EstadoVazio";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { ApiError } from "@/lib/api/client";
import { buscarResumoAnalise } from "@/lib/api/endpoints";
import { competenciaValida, rotuloCompetencia } from "@/lib/competencia";
import { ROTULO_GATILHO, formatarDataHora } from "@/lib/formato";
import type { Competencia, ResumoAnalise } from "@/types/api";

export async function generateMetadata(props: PageProps<"/analises/[competencia]">): Promise<Metadata> {
  const { competencia } = await props.params;
  return { title: competenciaValida(competencia) ? `Dashboard — ${rotuloCompetencia(competencia)}` : "Dashboard" };
}

type Carregamento =
  | { estado: "ok"; resumo: ResumoAnalise }
  | { estado: "sem-analise" }
  | { estado: "erro"; erro: ApiError };

/** Busca no servidor: a tela chega pronta, sem "carregando". */
async function carregar(competencia: Competencia): Promise<Carregamento> {
  try {
    return { estado: "ok", resumo: await buscarResumoAnalise(competencia) };
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

/** Painel do mês: inconformidades por gravidade, pessoas afetadas, banco de horas e carga. */
export default async function Dashboard(props: PageProps<"/analises/[competencia]">) {
  const { competencia } = await props.params;
  if (!competenciaValida(competencia)) {
    notFound();
  }

  const resultado = await carregar(competencia);

  return (
    <>
      <CabecalhoPagina
        titulo="Dashboard"
        subtitulo={
          // Sem análise, o mês já aparece no seletor ao lado: não repetir.
          resultado.estado === "ok"
            ? `Análise de ${formatarDataHora(resultado.resumo.executadaEm)} · ${ROTULO_GATILHO[resultado.resumo.gatilho]}`
            : undefined
        }
      >
        <SeletorCompetencia competencia={competencia} />
      </CabecalhoPagina>

      {resultado.estado === "sem-analise" && (
        <EstadoVazio
          icone={CalendarX2}
          titulo="Nenhuma análise gerada para este mês"
          descricao={`Ainda não há escala importada para ${rotuloCompetencia(competencia)}. Importe a escala do setor (e o relatório de Banco de Horas do Two) para gerar a análise.`}
          acao={{ href: "/importacoes", rotulo: "Ir para Importações", icone: FileUp }}
        />
      )}

      {resultado.estado === "erro" && <ErroCarregamento erro={resultado.erro} oQue="a análise do mês" />}

      {resultado.estado === "ok" && (
        <section aria-label="Indicadores do mês" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <CardInconformidades resumo={resultado.resumo} competencia={competencia} />
          <CardColaboradoresAfetados resumo={resultado.resumo} />
          <CardBancoHoras resumo={resultado.resumo} />
          <CardDistribuicaoCarga resumo={resultado.resumo} />
        </section>
      )}
    </>
  );
}
