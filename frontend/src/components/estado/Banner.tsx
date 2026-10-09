"use client";

import { CircleAlert, Info, X } from "lucide-react";

import type { ApiError } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { CodigoErro } from "@/types/api";

/** Título por código; o `detail` do backend completa com o caso concreto. */
const TITULO_ERRO: Partial<Record<CodigoErro, string>> = {
  ARQUIVO_AUSENTE: "Nenhum arquivo recebido",
  ARQUIVO_INVALIDO: "Arquivo ilegível",
  ARQUIVO_MUITO_GRANDE: "Arquivo grande demais",
  PARAMETRO_INVALIDO: "Dado inválido no formulário",
  TIPO_INCOMPATIVEL: "Arquivo enviado no card errado",
  FORMATO_NAO_RECONHECIDO: "Layout não reconhecido",
  EXTRACAO_FALHOU: "Não foi possível ler o conteúdo",
  EXTRATOR_INDISPONIVEL: "Serviço de leitura de arquivos fora do ar",
  SEM_CONEXAO: "Sem conexão com o servidor",
  ACESSO_NEGADO: "Seu perfil não permite esta operação",
  EMAIL_JA_CADASTRADO: "E-mail já cadastrado",
  ULTIMO_ADMIN: "Último administrador ativo",
  ALTERACAO_PROPRIA_BLOQUEADA: "Alteração na própria conta bloqueada",
  USUARIO_NAO_ENCONTRADO: "Usuário não encontrado",
};

export function Banner({
  tom,
  icone: Icone,
  titulo,
  aoFechar,
  children,
}: {
  tom: "verde" | "azul" | "vermelho";
  icone: typeof Info;
  titulo: string;
  aoFechar: () => void;
  children: React.ReactNode;
}) {
  const cores = {
    verde: "border-emerald-200 bg-emerald-50 text-emerald-900 [--icone:var(--color-emerald-600)]",
    azul: "border-sky-200 bg-sky-50 text-sky-900 [--icone:var(--color-sky-600)]",
    vermelho: "border-red-200 bg-red-50 text-red-900 [--icone:var(--color-red-600)]",
  }[tom];
  return (
    <div role={tom === "vermelho" ? "alert" : "status"} className={cn("rounded-lg border p-4 text-sm", cores)}>
      <div className="flex items-start gap-3">
        <Icone className="mt-0.5 size-5 shrink-0 text-(--icone)" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{titulo}</p>
          <div className="mt-1 space-y-2 opacity-90">{children}</div>
        </div>
        <button
          type="button"
          onClick={aoFechar}
          aria-label="Fechar aviso"
          className="-m-1 rounded p-1 opacity-60 hover:opacity-100"
        >
          <X className="size-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}

/**
 * Erro da API: título pelo `codigo`, texto pelo `detail` do backend.
 * @param tituloPadrao usado quando o código não tem título próprio
 */
export function BannerErro({
  erro,
  aoFechar,
  tituloPadrao = "Não foi possível concluir",
}: {
  erro: ApiError;
  aoFechar: () => void;
  tituloPadrao?: string;
}) {
  const codigo = erro.codigo;
  return (
    <Banner
      tom="vermelho"
      icone={CircleAlert}
      titulo={(codigo && TITULO_ERRO[codigo]) ?? erro.problema.title ?? tituloPadrao}
      aoFechar={aoFechar}
    >
      <p>{erro.problema.detail ?? erro.message}</p>
      {codigo && (
        <p className="font-mono text-xs opacity-75">
          {codigo}
          {erro.status > 0 && ` · HTTP ${erro.status}`}
        </p>
      )}
    </Banner>
  );
}
