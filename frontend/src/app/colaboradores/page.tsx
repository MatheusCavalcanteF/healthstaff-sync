import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { FileUp, Users } from "lucide-react";

import { FiltrosColaboradores } from "@/components/colaboradores/FiltrosColaboradores";
import { TabelaColaboradores } from "@/components/colaboradores/TabelaColaboradores";
import { ErroCarregamento } from "@/components/estado/ErroCarregamento";
import { EstadoVazio } from "@/components/estado/EstadoVazio";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { ApiError } from "@/lib/api/client";
import { listarColaboradores } from "@/lib/api/endpoints";
import { TAMANHO_PAGINA, paginaDaUrl, primeiro, vinculoValido } from "@/lib/colaboradores";
import type { Colaborador, FiltrosColaborador, Pagina } from "@/types/api";

export const metadata: Metadata = { title: "Colaboradores" };

type Carregamento = { estado: "ok"; pagina: Pagina<Colaborador> } | { estado: "erro"; erro: ApiError };

async function carregar(filtros: FiltrosColaborador): Promise<Carregamento> {
  try {
    return { estado: "ok", pagina: await listarColaboradores(filtros) };
  } catch (erro) {
    if (erro instanceof ApiError) {
      return { estado: "erro", erro };
    }
    throw erro;
  }
}

/** URL da tela com os filtros dados (só os preenchidos; página 0 fica implícita). */
function urlColaboradores({ busca, vinculo, pagina }: FiltrosColaborador): string {
  const params = new URLSearchParams();
  if (busca) params.set("busca", busca);
  if (vinculo) params.set("vinculo", vinculo);
  if (pagina) params.set("pagina", String(pagina));
  const consulta = params.toString();
  return consulta ? `/colaboradores?${consulta}` : "/colaboradores";
}

/** Cadastro: vínculo oficial (Two) ou provisório (só na escala), categoria e saldo. */
export default async function Colaboradores(props: PageProps<"/colaboradores">) {
  const parametros = await props.searchParams;
  const vinculo = primeiro(parametros.vinculo);
  const filtros: FiltrosColaborador = {
    busca: primeiro(parametros.busca)?.trim() || undefined,
    // Valor inválido na URL é ignorado (lista sem aquele filtro), não erro.
    vinculo: vinculoValido(vinculo) ? vinculo : undefined,
    pagina: paginaDaUrl(primeiro(parametros.pagina)),
    tamanho: TAMANHO_PAGINA,
  };

  const resultado = await carregar(filtros);

  // Link antigo para uma página que deixou de existir: vai para a última.
  if (resultado.estado === "ok" && resultado.pagina.conteudo.length === 0 && resultado.pagina.totalPaginas > 0) {
    redirect(urlColaboradores({ ...filtros, pagina: resultado.pagina.totalPaginas - 1 }));
  }

  const algumFiltro = Boolean(filtros.busca || filtros.vinculo);

  return (
    <>
      <CabecalhoPagina
        titulo="Colaboradores"
        subtitulo="Cadastro oficial (Two) e provisório (somente na escala), com saldo de banco de horas"
      />

      {resultado.estado === "erro" && <ErroCarregamento erro={resultado.erro} oQue="os colaboradores" />}

      {resultado.estado === "ok" && resultado.pagina.totalElementos === 0 && !algumFiltro && (
        <EstadoVazio
          icone={Users}
          titulo="Nenhum colaborador cadastrado"
          descricao="O cadastro é formado pelo relatório de Banco de Horas do Two e pelas escalas importadas."
          acao={{ href: "/importacoes", rotulo: "Ir para Importações", icone: FileUp }}
        />
      )}

      {resultado.estado === "ok" && (resultado.pagina.totalElementos > 0 || algumFiltro) && (
        <FiltrosColaboradores busca={filtros.busca ?? ""} vinculo={filtros.vinculo}>
          {resultado.pagina.totalElementos === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
              <p className="font-medium text-slate-900">Nenhum colaborador encontrado</p>
              <p className="mt-1 text-sm text-slate-500">Confira o nome ou a matrícula, ou mude o filtro de vínculo.</p>
            </div>
          ) : (
            <TabelaColaboradores
              pagina={resultado.pagina}
              hrefPagina={(pagina) => urlColaboradores({ ...filtros, pagina })}
            />
          )}
        </FiltrosColaboradores>
      )}
    </>
  );
}
