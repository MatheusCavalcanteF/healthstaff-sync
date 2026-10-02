import type { Metadata } from "next";

import { PainelImportacoes } from "@/components/importacoes/PainelImportacoes";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";

export const metadata: Metadata = { title: "Importações" };

/** Envio da escala (PDF/Excel) e do relatório de Banco de Horas do Two (Excel/CSV). */
export default function Importacoes() {
  return (
    <>
      <CabecalhoPagina
        titulo="Importações"
        subtitulo="Envie a escala do setor e o relatório de Banco de Horas do Two; a análise do mês é refeita a cada envio"
      />
      <PainelImportacoes />
    </>
  );
}
