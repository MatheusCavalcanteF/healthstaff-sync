"use client";

import { useActionState } from "react";
import { LoaderCircle, LogIn } from "lucide-react";

import { entrar, type EstadoLogin } from "@/app/login/actions";

const CAMPO =
  "h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 shadow-xs focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 focus:outline-none";

export function FormularioLogin({ proximo }: { proximo: string }) {
  const [estado, acao, enviando] = useActionState<EstadoLogin, FormData>(entrar, {});

  return (
    <form action={acao} className="mt-6 flex flex-col gap-4">
      <input type="hidden" name="proximo" value={proximo} />

      <div className="flex flex-col gap-1.5">
        <label htmlFor="email" className="text-sm font-medium text-slate-700">
          E-mail
        </label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          required
          defaultValue={estado.email}
          className={CAMPO}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <label htmlFor="senha" className="text-sm font-medium text-slate-700">
          Senha
        </label>
        <input id="senha" name="senha" type="password" autoComplete="current-password" required className={CAMPO} />
      </div>

      {estado.erro && (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800">
          {estado.erro}
        </p>
      )}

      <button
        type="submit"
        disabled={enviando}
        className="mt-2 inline-flex h-10 items-center justify-center gap-2 rounded-md bg-teal-600 px-4 text-sm font-medium text-white shadow-sm transition-colors hover:bg-teal-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-600 disabled:cursor-not-allowed disabled:opacity-70"
      >
        {enviando ? (
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
        ) : (
          <LogIn className="size-4" aria-hidden />
        )}
        {enviando ? "Entrando..." : "Entrar"}
      </button>
    </form>
  );
}
