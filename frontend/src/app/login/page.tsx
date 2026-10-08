import type { Metadata } from "next";
import { HeartPulse } from "lucide-react";

import { FormularioLogin } from "@/components/login/FormularioLogin";
import { primeiro } from "@/lib/colaboradores";

export const metadata: Metadata = { title: "Entrar" };

export default async function Login(props: PageProps<"/login">) {
  const parametros = await props.searchParams;
  const sessaoExpirada = primeiro(parametros.motivo) === "sessao-expirada";

  return (
    <div className="flex min-h-full items-center justify-center py-10">
      <section aria-labelledby="titulo-login" className="w-full max-w-sm">
        <div className="mb-6 flex items-center justify-center gap-2">
          <HeartPulse className="size-7 text-teal-600" aria-hidden />
          <p className="text-lg font-semibold text-slate-900">HealthStaff Sync</p>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white px-6 py-7 shadow-sm">
          <h1 id="titulo-login" className="text-lg font-semibold text-slate-900">
            Entrar
          </h1>
          <p className="mt-1 text-sm text-slate-500">Use o e-mail e a senha cadastrados para você.</p>

          {sessaoExpirada && (
            <p role="status" className="mt-4 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800">
              Sua sessão expirou. Entre novamente para continuar.
            </p>
          )}

          <FormularioLogin proximo={primeiro(parametros.proximo) ?? ""} />
        </div>
      </section>
    </div>
  );
}
