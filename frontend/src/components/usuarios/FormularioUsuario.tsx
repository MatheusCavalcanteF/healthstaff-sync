"use client";

import { useEffect, useRef, useState } from "react";
import { LoaderCircle, X } from "lucide-react";

import { BannerErro } from "@/components/estado/Banner";
import type { ApiError } from "@/lib/api/client";
import { atualizarUsuario, criarUsuario } from "@/lib/api/navegador";
import { PERFIS, ROTULO_PERFIL } from "@/lib/auth";
import type { Perfil, Usuario } from "@/types/api";

const CAMPO =
  "h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 shadow-xs focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 focus:outline-none disabled:bg-slate-50 disabled:text-slate-500";

/** Mesma regra do backend (PoliticaSenha). */
const SENHA_MINIMA = 12;

type Formulario = { modo: "novo" } | { modo: "editar"; usuario: Usuario } | null;

interface FormularioUsuarioProps {
  formulario: Formulario;
  /** Editando a própria conta: o perfil fica travado (o backend também recusa). */
  proprio: boolean;
  aoFechar: () => void;
  aoSalvar: (usuario: Usuario, modo: "novo" | "editar") => void;
  /** Devolve o erro para exibir, ou null se já foi tratado (ex.: sessão expirada). */
  tratarErro: (falha: unknown) => ApiError | null;
}

/** Modal de cadastro/edição. Usa o <dialog> nativo: foco preso, Esc fecha, fundo inerte. */
export function FormularioUsuario({ formulario, proprio, aoFechar, aoSalvar, tratarErro }: FormularioUsuarioProps) {
  const dialogo = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const el = dialogo.current;
    if (!el) {
      return;
    }
    if (formulario && !el.open) {
      el.showModal();
    } else if (!formulario && el.open) {
      el.close();
    }
  }, [formulario]);

  return (
    <dialog
      ref={dialogo}
      aria-labelledby="formulario-usuario-titulo"
      onClose={aoFechar}
      className="m-auto w-full max-w-md rounded-xl bg-white p-0 shadow-2xl backdrop:bg-slate-900/40"
    >
      {formulario && (
        // A key recria o formulário (e limpa os campos) a cada abertura.
        <Conteudo
          key={formulario.modo === "editar" ? formulario.usuario.id : "novo"}
          formulario={formulario}
          proprio={proprio}
          aoFechar={() => dialogo.current?.close()}
          aoSalvar={aoSalvar}
          tratarErro={tratarErro}
        />
      )}
    </dialog>
  );
}

function Conteudo({
  formulario,
  proprio,
  aoFechar,
  aoSalvar,
  tratarErro,
}: Omit<FormularioUsuarioProps, "formulario"> & { formulario: NonNullable<Formulario> }) {
  const editando = formulario.modo === "editar" ? formulario.usuario : null;
  const [nome, setNome] = useState(editando?.nome ?? "");
  const [email, setEmail] = useState(editando?.email ?? "");
  const [senha, setSenha] = useState("");
  const [perfil, setPerfil] = useState<Perfil>(editando?.perfil ?? "GESTOR_RH");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<ApiError | null>(null);

  async function aoEnviar(evento: React.FormEvent) {
    evento.preventDefault();
    if (enviando) {
      return;
    }
    setEnviando(true);
    setErro(null);
    try {
      const salvo = editando
        ? await atualizarUsuario(editando.id, { nome, email, perfil, senha })
        : await criarUsuario({ nome, email, senha, perfil });
      aoSalvar(salvo, formulario.modo);
    } catch (falha) {
      setErro(tratarErro(falha));
    } finally {
      setEnviando(false);
    }
  }

  const avisoSessao =
    editando && (senha || email.trim().toLowerCase() !== editando.email || perfil !== editando.perfil);

  return (
    <form onSubmit={aoEnviar} className="flex flex-col">
      <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-4">
        <h2 id="formulario-usuario-titulo" className="text-lg font-semibold text-slate-900">
          {editando ? `Editar ${editando.nome}` : "Novo Usuário"}
        </h2>
        <button
          type="button"
          onClick={aoFechar}
          aria-label="Fechar"
          className="-m-1 rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
        >
          <X className="size-5" aria-hidden />
        </button>
      </header>

      <div className="flex flex-col gap-4 px-6 py-5">
        {erro && <BannerErro erro={erro} aoFechar={() => setErro(null)} />}

        <Campo id="usuario-nome" rotulo="Nome">
          <input
            id="usuario-nome"
            value={nome}
            onChange={(e) => setNome(e.target.value)}
            required
            maxLength={255}
            autoComplete="off"
            className={CAMPO}
          />
        </Campo>

        <Campo id="usuario-email" rotulo="E-mail">
          <input
            id="usuario-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            maxLength={255}
            autoComplete="off"
            className={CAMPO}
          />
        </Campo>

        <Campo
          id="usuario-senha"
          rotulo={editando ? "Nova senha" : "Senha"}
          ajuda={
            editando
              ? `Deixe em branco para manter a senha atual. Se preencher, no mínimo ${SENHA_MINIMA} caracteres.`
              : `No mínimo ${SENHA_MINIMA} caracteres. Passe-a à pessoa por um canal seguro.`
          }
        >
          <input
            id="usuario-senha"
            type="password"
            value={senha}
            onChange={(e) => setSenha(e.target.value)}
            required={!editando}
            minLength={SENHA_MINIMA}
            autoComplete="new-password"
            className={CAMPO}
          />
        </Campo>

        <Campo
          id="usuario-perfil"
          rotulo="Perfil"
          ajuda={proprio ? "Você não pode alterar o próprio perfil; peça a outro administrador." : undefined}
        >
          <select
            id="usuario-perfil"
            value={perfil}
            onChange={(e) => setPerfil(e.target.value as Perfil)}
            disabled={proprio}
            className={CAMPO}
          >
            {PERFIS.map((p) => (
              <option key={p} value={p}>
                {ROTULO_PERFIL[p]}
              </option>
            ))}
          </select>
        </Campo>

        {avisoSessao && (
          <p className="rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800">
            Trocar senha, e-mail ou perfil encerra as sessões abertas desta pessoa: ela precisará entrar de novo.
          </p>
        )}
      </div>

      <footer className="flex justify-end gap-2 border-t border-slate-200 px-6 py-4">
        <button
          type="button"
          onClick={aoFechar}
          className="rounded-md px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100"
        >
          Cancelar
        </button>
        <button
          type="submit"
          disabled={enviando}
          className="inline-flex items-center gap-2 rounded-md bg-teal-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-70"
        >
          {enviando && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
          {editando ? "Salvar" : "Cadastrar"}
        </button>
      </footer>
    </form>
  );
}

function Campo({
  id,
  rotulo,
  ajuda,
  children,
}: {
  id: string;
  rotulo: string;
  ajuda?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-slate-700">
        {rotulo}
      </label>
      {children}
      {ajuda && <p className="text-xs text-slate-500">{ajuda}</p>}
    </div>
  );
}
