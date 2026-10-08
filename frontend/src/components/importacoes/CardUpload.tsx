"use client";

import { useId, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { FileCheck2, LoaderCircle, Upload, X, type LucideIcon } from "lucide-react";

import { BannerErro, BannerSucesso } from "@/components/importacoes/Banners";
import { ApiError } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { ImportacaoResponse } from "@/types/api";

/** Excel 97-2003: o extrator (openpyxl) só lê .xlsx/.xlsm. */
const EXTENSOES_LEGADAS = [".xls"];

type Resultado =
  | { estado: "sucesso"; resposta: ImportacaoResponse }
  | { estado: "erro"; erro: ApiError }
  | null;

interface CardUploadProps {
  titulo: string;
  descricao: string;
  icone: LucideIcon;
  /** Extensões aceitas, com ponto (".pdf"). */
  extensoes: string[];
  /** Campos além do arquivo (ex.: data de referência). */
  extras?: React.ReactNode;
  /** Texto de ajuda abaixo dos extras, calculado a partir do arquivo escolhido. */
  ajuda?: (arquivo: File | null) => React.ReactNode;
  enviar: (arquivo: File) => Promise<ImportacaoResponse>;
  /** Chamado após um envio bem-sucedido, para limpar os campos extras. */
  aoConcluir?: () => void;
}

function extensao(nome: string): string {
  const ponto = nome.lastIndexOf(".");
  return ponto < 0 ? "" : nome.slice(ponto).toLowerCase();
}

function tamanhoLegivel(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** Card com área de arrastar-e-soltar, botão de envio e resultado da importação. */
export function CardUpload({ titulo, descricao, icone: Icone, extensoes, extras, ajuda, enviar, aoConcluir }: CardUploadProps) {
  const id = useId();
  const roteador = useRouter();
  const caminho = usePathname();
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [arrastando, setArrastando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState<Resultado>(null);
  const [avisoLocal, setAvisoLocal] = useState<string | null>(null);
  // Trocar a key recria o <input type="file">: permite escolher o mesmo arquivo de novo.
  const [versaoInput, setVersaoInput] = useState(0);

  function escolher(novo: File | null | undefined) {
    setResultado(null);
    setAvisoLocal(null);
    if (!novo) {
      return;
    }
    const ext = extensao(novo.name);
    if (EXTENSOES_LEGADAS.includes(ext)) {
      setAvisoLocal("Arquivos .xls (Excel 97-2003) não são lidos. Abra no Excel e use “Salvar como” .xlsx.");
      return;
    }
    if (!extensoes.includes(ext)) {
      setAvisoLocal(`Formato ${ext || "sem extensão"} não aceito aqui. Envie ${extensoes.join(", ")}.`);
      return;
    }
    setArquivo(novo);
  }

  function limpar() {
    setArquivo(null);
    setVersaoInput((v) => v + 1);
  }

  async function aoEnviar(evento: React.FormEvent) {
    evento.preventDefault();
    if (!arquivo || enviando) {
      return;
    }
    setEnviando(true);
    setResultado(null);
    try {
      const resposta = await enviar(arquivo);
      setResultado({ estado: "sucesso", resposta });
      limpar();
      aoConcluir?.();
    } catch (erro) {
      // O client converte toda falha em ApiError; o resto é bug e deve aparecer.
      if (!(erro instanceof ApiError)) {
        throw erro;
      }
      if (erro.naoAutenticado) {
        // Sessão expirou com a tela aberta: login e volta para cá.
        roteador.push(`/login?motivo=sessao-expirada&proximo=${encodeURIComponent(caminho)}`);
        return;
      }
      setResultado({ estado: "erro", erro });
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section
      aria-labelledby={`${id}-titulo`}
      className="flex flex-col rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
    >
      <div className="flex items-start gap-3">
        <div className="rounded-lg bg-teal-50 p-2.5">
          <Icone className="size-5 text-teal-600" aria-hidden />
        </div>
        <div>
          <h2 id={`${id}-titulo`} className="font-semibold text-slate-900">
            {titulo}
          </h2>
          <p className="mt-0.5 text-sm text-slate-500">{descricao}</p>
        </div>
      </div>

      <form onSubmit={aoEnviar} className="mt-5 flex flex-1 flex-col gap-4">
        <div>
          {/* sr-only (e não display:none) mantém o input no Tab: a área ganha anel de foco. */}
          <input
            key={versaoInput}
            id={`${id}-arquivo`}
            type="file"
            accept={extensoes.join(",")}
            disabled={enviando}
            onChange={(e) => escolher(e.target.files?.[0])}
            className="peer sr-only"
          />
          <label
            htmlFor={`${id}-arquivo`}
            onDragOver={(e) => {
              e.preventDefault();
              if (!enviando) setArrastando(true);
            }}
            onDragLeave={() => setArrastando(false)}
            onDrop={(e) => {
              e.preventDefault();
              setArrastando(false);
              if (!enviando) escolher(e.dataTransfer.files[0]);
            }}
            className={cn(
              "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-4 py-8 text-center transition-colors",
              "peer-focus-visible:ring-2 peer-focus-visible:ring-teal-500 peer-focus-visible:ring-offset-2",
              arrastando
                ? "border-teal-500 bg-teal-50"
                : arquivo
                  ? "border-teal-300 bg-teal-50/40"
                  : "border-slate-300 bg-slate-50 hover:border-teal-400 hover:bg-teal-50/30",
              enviando && "cursor-not-allowed opacity-60",
            )}
          >
            {arquivo ? (
              <>
                <FileCheck2 className="size-8 text-teal-600" aria-hidden />
                <span className="max-w-full truncate text-sm font-medium text-slate-900">{arquivo.name}</span>
                <span className="text-xs text-slate-500">
                  {tamanhoLegivel(arquivo.size)} · clique ou arraste para trocar
                </span>
              </>
            ) : (
              <>
                <Upload className="size-8 text-slate-400" aria-hidden />
                <span className="text-sm text-slate-700">
                  <span className="font-medium text-teal-700">Clique para escolher</span> ou arraste o arquivo aqui
                </span>
                <span className="text-xs text-slate-500">{extensoes.join(", ")}</span>
              </>
            )}
          </label>
          {arquivo && !enviando && (
            <button
              type="button"
              onClick={limpar}
              className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-900"
            >
              <X className="size-3.5" aria-hidden />
              Remover arquivo
            </button>
          )}
          {avisoLocal && (
            <p role="alert" className="mt-2 text-sm text-red-700">
              {avisoLocal}
            </p>
          )}
        </div>

        {extras}
        {ajuda?.(arquivo)}

        <button
          type="submit"
          disabled={!arquivo || enviando}
          className="mt-auto inline-flex h-10 items-center justify-center gap-2 rounded-md bg-teal-600 px-4 text-sm font-medium text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-600 disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {enviando ? (
            <>
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
              Enviando e processando…
            </>
          ) : (
            <>
              <Upload className="size-4" aria-hidden />
              Enviar arquivo
            </>
          )}
        </button>
      </form>

      <div aria-live="polite" className="empty:hidden mt-4">
        {resultado?.estado === "sucesso" && (
          <BannerSucesso resposta={resultado.resposta} aoFechar={() => setResultado(null)} />
        )}
        {resultado?.estado === "erro" && <BannerErro erro={resultado.erro} aoFechar={() => setResultado(null)} />}
      </div>
    </section>
  );
}
