import type { Metadata } from "next";

import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";

export const metadata: Metadata = { title: "Importações" };

/** Envio da escala (PDF/Excel) e do relatório de Banco de Horas do Two (Excel/CSV). */
export default function Importacoes() {
  return (
    <CabecalhoPagina
      titulo="Importações"
      subtitulo="Envie a escala do setor e o relatório de Banco de Horas do Two"
    />
  );
}
