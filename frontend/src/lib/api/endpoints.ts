import "server-only";

import { apiServidor as api } from "@/lib/api/servidor";
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
  TokenResponse,
  Usuario,
  UUID,
} from "@/types/api";

/**
 * Chamadas tipadas ao java-backend, feitas no servidor do Next (com o token da
 * sessão). Erros chegam como `ApiError`. Uploads do navegador: `navegador.ts`.
 */

export async function autenticar(email: string, senha: string): Promise<TokenResponse> {
  const { data } = await api.post<TokenResponse>("/auth/login", { email, senha });
  return data;
}

/** Só ADMIN (os demais recebem 403 ACESSO_NEGADO). */
export async function listarUsuarios(): Promise<Usuario[]> {
  const { data } = await api.get<Usuario[]>("/usuarios");
  return data;
}

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

export async function buscarImportacao(id: UUID): Promise<ImportacaoResponse> {
  const { data } = await api.get<ImportacaoResponse>(`/importacoes/${id}`);
  return data;
}
