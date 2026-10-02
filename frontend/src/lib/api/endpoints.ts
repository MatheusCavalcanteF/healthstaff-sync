import { format } from "date-fns";

import { api } from "@/lib/api/client";
import type {
  Colaborador,
  Competencia,
  FiltrosColaborador,
  FiltrosInconformidade,
  ImportacaoResponse,
  Inconformidade,
  Pagina,
  ResumoAnalise,
  ResumoReprocessamento,
  UUID,
} from "@/types/api";

/** Chamadas tipadas ao java-backend. Erros chegam como `ApiError`. */

export async function buscarResumoAnalise(competencia: Competencia): Promise<ResumoAnalise> {
  const { data } = await api.get<ResumoAnalise>(`/analises/${competencia}`);
  return data;
}

export async function listarInconformidades(
  competencia: Competencia,
  filtros: FiltrosInconformidade = {},
): Promise<Inconformidade[]> {
  const { data } = await api.get<Inconformidade[]>(`/analises/${competencia}/inconformidades`, {
    params: filtros,
  });
  return data;
}

export async function reprocessarAnalise(competencia: Competencia): Promise<ResumoReprocessamento> {
  const { data } = await api.post<ResumoReprocessamento>(`/analises/${competencia}`);
  return data;
}

export async function listarColaboradores(
  filtros: FiltrosColaborador = {},
): Promise<Pagina<Colaborador>> {
  const { data } = await api.get<Pagina<Colaborador>>("/colaboradores", { params: filtros });
  return data;
}

/**
 * Uploads: no navegador o Axios troca este cabeçalho pelo que o próprio browser
 * gera para o FormData, com o `boundary` (escrito à mão, ele faltaria).
 */
const MULTIPART = { headers: { "Content-Type": "multipart/form-data" } };

export async function importarEscala(arquivo: File): Promise<ImportacaoResponse> {
  const formulario = new FormData();
  formulario.append("arquivo", arquivo);
  const { data } = await api.post<ImportacaoResponse>("/importacoes/escalas", formulario, MULTIPART);
  return data;
}

/**
 * @param dataReferencia AAAA-MM-DD; o padrão é a data de modificação do arquivo, no fuso
 *                       local (o relatório do Two não traz a data de emissão).
 */
export async function importarPonto(
  arquivo: File,
  // toISOString() usaria UTC: um arquivo salvo às 22h de 30/09 (UTC-3) viraria 01/10.
  dataReferencia: string = format(new Date(arquivo.lastModified), "yyyy-MM-dd"),
): Promise<ImportacaoResponse> {
  const formulario = new FormData();
  formulario.append("arquivo", arquivo);
  formulario.append("dataReferencia", dataReferencia);
  const { data } = await api.post<ImportacaoResponse>("/importacoes/ponto", formulario, MULTIPART);
  return data;
}

export async function buscarImportacao(id: UUID): Promise<ImportacaoResponse> {
  const { data } = await api.get<ImportacaoResponse>(`/importacoes/${id}`);
  return data;
}
