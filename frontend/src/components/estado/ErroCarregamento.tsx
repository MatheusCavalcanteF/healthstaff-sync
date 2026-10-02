import { ServerCrash } from "lucide-react";

import type { ApiError } from "@/lib/api/client";

/** Falha ao falar com o backend (fora do ar, erro interno...). Distinto de "sem dados". */
export function ErroCarregamento({ erro, oQue }: { erro: ApiError; oQue: string }) {
  return (
    <section
      role="alert"
      className="flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-5 py-4 text-sm text-red-800"
    >
      <ServerCrash className="mt-0.5 size-5 shrink-0" aria-hidden />
      <div>
        <p className="font-semibold">Não foi possível carregar {oQue}.</p>
        <p className="mt-1">
          {erro.semConexao
            ? "O servidor não respondeu. Verifique se o backend está no ar e tente novamente."
            : erro.message}
        </p>
        {erro.codigo && <p className="mt-2 font-mono text-xs text-red-600">código: {erro.codigo}</p>}
      </div>
    </section>
  );
}
