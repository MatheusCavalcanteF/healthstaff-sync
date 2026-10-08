"use client";

import Link from "next/link";
import { ArrowRight, CircleAlert, CircleCheck, Info, X } from "lucide-react";

import type { ApiError } from "@/lib/api/client";
import { rotuloCompetencia } from "@/lib/competencia";
import { formatarDataHora, formatarDia, formatarNumero } from "@/lib/formato";
import { cn } from "@/lib/utils";
import type { CodigoErro, Competencia, ImportacaoResponse } from "@/types/api";

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
  ACESSO_NEGADO: "Seu perfil não pode importar",
};

function Banner({
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

export function BannerErro({ erro, aoFechar }: { erro: ApiError; aoFechar: () => void }) {
  const codigo = erro.codigo;
  return (
    <Banner
      tom="vermelho"
      icone={CircleAlert}
      titulo={(codigo && TITULO_ERRO[codigo]) ?? erro.problema.title ?? "Falha na importação"}
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

/** "2026-10-01" -> "2026-10". */
function competenciaDe(data: string): Competencia {
  return data.slice(0, 7) as Competencia;
}

export function BannerSucesso({ resposta, aoFechar }: { resposta: ImportacaoResponse; aoFechar: () => void }) {
  const ehEscala = resposta.escalas.length > 0 || resposta.tipoDocumento.startsWith("ESCALA");
  const avisos = resposta.inconsistencias.filter((i) => i.severidade !== "INFO");
  const competencia = competenciaDe(resposta.escalas[0]?.competencia ?? resposta.periodoInicio);

  if (resposta.duplicada) {
    return (
      <Banner tom="azul" icone={Info} titulo="Este arquivo já tinha sido importado" aoFechar={aoFechar}>
        <p>
          <strong className="font-medium">{resposta.nomeArquivo}</strong> é idêntico a uma importação de{" "}
          {formatarDataHora(resposta.importadoEm)}. Nada foi alterado.
        </p>
      </Banner>
    );
  }

  return (
    <Banner tom="verde" icone={CircleCheck} titulo="Importação concluída" aoFechar={aoFechar}>
      <p>
        <strong className="font-medium">{resposta.nomeArquivo}</strong> · período de {formatarDia(resposta.periodoInicio)}{" "}
        a {formatarDia(resposta.periodoFim)}
      </p>
      <ul className="list-inside list-disc">
        <li>{formatarNumero(resposta.totalColaboradores)} colaboradores</li>
        {ehEscala ? (
          <>
            <li>{formatarNumero(resposta.totalPlantoes)} plantões</li>
            {resposta.escalas.map((e) => (
              <li key={`${e.setor}-${e.versao}`}>
                {e.setor}: versão {e.versao}
                {e.vigente ? " (vigente)" : ""}
              </li>
            ))}
          </>
        ) : (
          <li>{formatarNumero(resposta.saldos)} saldos de banco de horas</li>
        )}
      </ul>
      {avisos.length > 0 && (
        <details className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900">
          <summary className="cursor-pointer font-medium">
            {avisos.length === 1 ? "1 ponto para conferir" : `${formatarNumero(avisos.length)} pontos para conferir`}
          </summary>
          <ul className="mt-2 max-h-48 space-y-1 overflow-y-auto text-xs">
            {avisos.map((a, i) => (
              <li key={i}>
                <span className="font-semibold">{a.severidade === "ERRO" ? "Erro" : "Aviso"}:</span> {a.mensagem}
              </li>
            ))}
          </ul>
        </details>
      )}
      <Link
        href={`/analises/${competencia}`}
        className="inline-flex items-center gap-1 font-medium text-emerald-800 underline-offset-2 hover:underline"
      >
        Ver análise de {rotuloCompetencia(competencia)}
        <ArrowRight className="size-4" aria-hidden />
      </Link>
    </Banner>
  );
}
