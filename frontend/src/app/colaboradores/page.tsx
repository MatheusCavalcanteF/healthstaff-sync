import type { Metadata } from "next";

import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";

export const metadata: Metadata = { title: "Colaboradores" };

/** Cadastro: vínculo oficial (Two) ou provisório (só na escala), categoria e saldo. */
export default function Colaboradores() {
  return (
    <CabecalhoPagina
      titulo="Colaboradores"
      subtitulo="Cadastro oficial (Two) e provisório (somente na escala), com saldo de banco de horas"
    />
  );
}
