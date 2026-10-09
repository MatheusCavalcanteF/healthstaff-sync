import { format } from "date-fns";

import { criarApi } from "@/lib/api/client";
import type { AtualizacaoUsuario, ImportacaoResponse, NovoUsuario, Usuario, UUID } from "@/types/api";

/**
 * Chamadas feitas no navegador (uploads). Vão para o repasse do próprio Next
 * (`/api/backend/...`), que acrescenta o token do cookie httpOnly: o
 * JavaScript da página nunca tem acesso a ele.
 */
const apiNavegador = criarApi("/api/backend");

/**
 * Uploads: no navegador o Axios troca este cabeçalho pelo que o próprio browser
 * gera para o FormData, com o `boundary` (escrito à mão, ele faltaria).
 */
const MULTIPART = { headers: { "Content-Type": "multipart/form-data" } };

export async function importarEscala(arquivo: File): Promise<ImportacaoResponse> {
  const formulario = new FormData();
  formulario.append("arquivo", arquivo);
  const { data } = await apiNavegador.post<ImportacaoResponse>("/importacoes/escalas", formulario, MULTIPART);
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
  const { data } = await apiNavegador.post<ImportacaoResponse>("/importacoes/ponto", formulario, MULTIPART);
  return data;
}

// Gestão de usuários (só ADMIN; os demais recebem 403 ACESSO_NEGADO).

export async function criarUsuario(dados: NovoUsuario): Promise<Usuario> {
  const { data } = await apiNavegador.post<Usuario>("/usuarios", dados);
  return data;
}

/** `senha` em branco mantém a senha atual. Trocar senha, e-mail ou perfil derruba as sessões do usuário. */
export async function atualizarUsuario(id: UUID, dados: AtualizacaoUsuario): Promise<Usuario> {
  const { data } = await apiNavegador.put<Usuario>(`/usuarios/${id}`, { ...dados, senha: dados.senha || undefined });
  return data;
}

export async function alterarStatusUsuario(id: UUID, ativo: boolean): Promise<Usuario> {
  const { data } = await apiNavegador.patch<Usuario>(`/usuarios/${id}/status`, { ativo });
  return data;
}
