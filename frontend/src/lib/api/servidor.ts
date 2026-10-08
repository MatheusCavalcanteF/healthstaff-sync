import "server-only";

import { redirect } from "next/navigation";

import { ApiError, API_URL, criarApi } from "@/lib/api/client";
import { lerToken } from "@/lib/sessao";

/**
 * Chamadas ao backend feitas pelo servidor do Next, com o token do cookie da
 * sessão. Sessão expirada ou revogada (401 NAO_AUTENTICADO) leva ao login em
 * vez de virar mensagem de erro na tela.
 */
export const apiServidor = criarApi(API_URL);

apiServidor.interceptors.request.use(async (config) => {
  const token = await lerToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiServidor.interceptors.response.use(
  (resposta) => resposta,
  (erro: unknown) => {
    if (erro instanceof ApiError && erro.naoAutenticado) {
      // Lança o redirecionamento do Next: as telas só capturam ApiError.
      redirect("/login?motivo=sessao-expirada");
    }
    return Promise.reject(erro);
  },
);
