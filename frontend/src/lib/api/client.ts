import axios, { AxiosError } from "axios";

import type { CodigoErro, ProblemDetail } from "@/types/api";

/** URL base do java-backend. Sobrescreva com NEXT_PUBLIC_API_URL (ver .env.example). */
export const API_URL =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

/**
 * Erro único para qualquer falha de chamada à API. As telas tratam
 * `erro.codigo` (ex.: "ANALISE_NAO_ENCONTRADA") sem conhecer o Axios.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problema: ProblemDetail,
  ) {
    super(problema.detail ?? problema.title ?? `Erro ${status} na API`);
    this.name = "ApiError";
  }

  get codigo(): CodigoErro | undefined {
    return this.problema.codigo;
  }

  /** Sem resposta do servidor (backend fora do ar, CORS, rede). */
  get semConexao(): boolean {
    return this.status === 0;
  }
}

export const api = axios.create({
  baseURL: API_URL,
  timeout: 130_000, // importações aguardam o extrator (timeout de leitura do backend: 120s)
  headers: { Accept: "application/json, application/problem+json" },
});

api.interceptors.response.use(
  (resposta) => resposta,
  (erro: unknown) => Promise.reject(paraApiError(erro)),
);

function paraApiError(erro: unknown): ApiError {
  if (erro instanceof ApiError) {
    return erro;
  }
  if (erro instanceof AxiosError) {
    const resposta = erro.response;
    if (!resposta) {
      return new ApiError(0, {
        status: 0,
        title: "Sem conexão com o servidor",
        detail: "Não foi possível falar com o backend. Verifique se ele está no ar.",
        codigo: "SEM_CONEXAO",
      });
    }
    const corpo = resposta.data as Partial<ProblemDetail> | undefined;
    if (corpo && typeof corpo === "object" && ("codigo" in corpo || "detail" in corpo)) {
      return new ApiError(resposta.status, { ...corpo, status: resposta.status });
    }
    return new ApiError(resposta.status, {
      status: resposta.status,
      title: resposta.statusText || "Erro na API",
      detail: `O servidor respondeu ${resposta.status}.`,
    });
  }
  return new ApiError(0, {
    status: 0,
    title: "Erro inesperado",
    detail: erro instanceof Error ? erro.message : String(erro),
  });
}
