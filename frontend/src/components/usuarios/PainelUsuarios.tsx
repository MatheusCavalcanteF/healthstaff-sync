"use client";

import { useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { CircleCheck, LoaderCircle, Pencil, Power, PowerOff, UserPlus } from "lucide-react";

import { Banner, BannerErro } from "@/components/estado/Banner";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { FormularioUsuario } from "@/components/usuarios/FormularioUsuario";
import { ApiError } from "@/lib/api/client";
import { alterarStatusUsuario } from "@/lib/api/navegador";
import { ROTULO_PERFIL } from "@/lib/auth";
import { formatarDataHora } from "@/lib/formato";
import { cn } from "@/lib/utils";
import type { Perfil, Usuario } from "@/types/api";

const COR_PERFIL: Record<Perfil, string> = {
  ADMIN: "bg-violet-50 text-violet-700 ring-violet-600/20",
  GESTOR_RH: "bg-teal-50 text-teal-700 ring-teal-600/20",
  AUDITOR: "bg-slate-100 text-slate-700 ring-slate-500/20",
};

type Formulario = { modo: "novo" } | { modo: "editar"; usuario: Usuario } | null;

interface PainelUsuariosProps {
  titulo: string;
  subtitulo: string;
  usuarios: Usuario[];
  /** Id do ADMIN logado: na própria linha não há desativar nem troca de perfil. */
  idLogado: string;
}

export function PainelUsuarios({ titulo, subtitulo, usuarios, idLogado }: PainelUsuariosProps) {
  const roteador = useRouter();
  const caminho = usePathname();
  const [formulario, setFormulario] = useState<Formulario>(null);
  const [erro, setErro] = useState<ApiError | null>(null);
  const [sucesso, setSucesso] = useState<string | null>(null);
  const [alterando, setAlterando] = useState<string | null>(null);

  /** Sessão expirada no meio da tela: login e volta para cá. */
  function tratarErro(falha: unknown): ApiError | null {
    if (!(falha instanceof ApiError)) {
      throw falha;
    }
    if (falha.naoAutenticado) {
      roteador.push(`/login?motivo=sessao-expirada&proximo=${encodeURIComponent(caminho)}`);
      return null;
    }
    return falha;
  }

  async function alternarStatus(usuario: Usuario) {
    setErro(null);
    setSucesso(null);
    setAlterando(usuario.id);
    try {
      const atualizado = await alterarStatusUsuario(usuario.id, !usuario.ativo);
      setSucesso(`${atualizado.nome} foi ${atualizado.ativo ? "ativado(a)" : "desativado(a)"}.`);
      roteador.refresh();
    } catch (falha) {
      setErro(tratarErro(falha));
    } finally {
      setAlterando(null);
    }
  }

  function aoSalvar(salvo: Usuario, modo: "novo" | "editar") {
    setFormulario(null);
    setErro(null);
    setSucesso(modo === "novo" ? `${salvo.nome} foi cadastrado(a).` : `Dados de ${salvo.nome} atualizados.`);
    roteador.refresh();
  }

  return (
    <>
      <CabecalhoPagina titulo={titulo} subtitulo={subtitulo}>
        <button
          type="button"
          onClick={() => setFormulario({ modo: "novo" })}
          className="inline-flex items-center gap-2 rounded-md bg-teal-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-600"
        >
          <UserPlus className="size-4" aria-hidden />
          Novo Usuário
        </button>
      </CabecalhoPagina>

      <div className="mb-4 space-y-3 empty:hidden">
        {erro && <BannerErro erro={erro} aoFechar={() => setErro(null)} />}
        {sucesso && (
          <Banner tom="verde" icone={CircleCheck} titulo="Pronto" aoFechar={() => setSucesso(null)}>
            <p>{sucesso}</p>
          </Banner>
        )}
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[48rem] text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs font-medium tracking-wide text-slate-500 uppercase">
              <tr>
                <th scope="col" className="px-4 py-3">Nome</th>
                <th scope="col" className="px-4 py-3">E-mail</th>
                <th scope="col" className="px-4 py-3">Perfil</th>
                <th scope="col" className="px-4 py-3">Status</th>
                <th scope="col" className="px-4 py-3 text-right">
                  <span className="sr-only">Ações</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {usuarios.map((u) => {
                const proprio = u.id === idLogado;
                return (
                  <tr key={u.id} className={cn("hover:bg-slate-50", !u.ativo && "text-slate-400")}>
                    <td className="px-4 py-3">
                      <div className={cn("font-medium", u.ativo ? "text-slate-900" : "text-slate-500")}>
                        {u.nome}
                        {proprio && <span className="ml-2 text-xs font-normal text-slate-400">(você)</span>}
                      </div>
                      <div className="text-xs text-slate-400">desde {formatarDataHora(u.dataCriacao)}</div>
                    </td>
                    <td className="px-4 py-3 text-slate-600">{u.email}</td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          "inline-flex rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                          COR_PERFIL[u.perfil],
                        )}
                      >
                        {ROTULO_PERFIL[u.perfil]}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          "inline-flex items-center gap-1.5 text-xs font-medium",
                          u.ativo ? "text-emerald-700" : "text-slate-500",
                        )}
                      >
                        <span
                          className={cn("size-1.5 rounded-full", u.ativo ? "bg-emerald-500" : "bg-slate-400")}
                          aria-hidden
                        />
                        {u.ativo ? "Ativo" : "Inativo"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => setFormulario({ modo: "editar", usuario: u })}
                          title={`Editar ${u.nome}`}
                          className="rounded-md p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-900"
                        >
                          <Pencil className="size-4" aria-hidden />
                          <span className="sr-only">Editar {u.nome}</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => alternarStatus(u)}
                          disabled={proprio || alterando !== null}
                          title={
                            proprio
                              ? "Você não pode desativar a própria conta"
                              : `${u.ativo ? "Desativar" : "Ativar"} ${u.nome}`
                          }
                          className={cn(
                            "rounded-md p-2 transition-colors disabled:cursor-not-allowed disabled:opacity-40",
                            u.ativo
                              ? "text-slate-500 hover:bg-red-50 hover:text-red-700"
                              : "text-slate-500 hover:bg-emerald-50 hover:text-emerald-700",
                          )}
                        >
                          {alterando === u.id ? (
                            <LoaderCircle className="size-4 animate-spin" aria-hidden />
                          ) : u.ativo ? (
                            <PowerOff className="size-4" aria-hidden />
                          ) : (
                            <Power className="size-4" aria-hidden />
                          )}
                          <span className="sr-only">
                            {u.ativo ? "Desativar" : "Ativar"} {u.nome}
                          </span>
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <FormularioUsuario
        formulario={formulario}
        proprio={formulario?.modo === "editar" && formulario.usuario.id === idLogado}
        aoFechar={() => setFormulario(null)}
        aoSalvar={aoSalvar}
        tratarErro={tratarErro}
      />
    </>
  );
}
