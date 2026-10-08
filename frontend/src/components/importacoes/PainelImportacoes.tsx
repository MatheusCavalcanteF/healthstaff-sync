"use client";

import { useState } from "react";
import { format } from "date-fns";
import { CalendarDays, Clock } from "lucide-react";

import { CardUpload } from "@/components/importacoes/CardUpload";
import { importarEscala, importarPonto } from "@/lib/api/navegador";
import { formatarDia } from "@/lib/formato";

/** Os dois envios do fluxo: escala do setor e relatório de Banco de Horas do Two. */
export function PainelImportacoes() {
  const [dataReferencia, setDataReferencia] = useState("");

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <CardUpload
        titulo="Importar Escala Mensal"
        descricao="Grade do setor em PDF ou Excel. Uma nova versão substitui a vigente do mesmo setor e mês."
        icone={CalendarDays}
        extensoes={[".pdf", ".xlsx", ".xlsm"]}
        enviar={importarEscala}
      />

      <CardUpload
        titulo="Importar Banco de Horas (Two)"
        descricao="Relatório “Banco de Horas” exportado do Two, em Excel ou CSV."
        icone={Clock}
        extensoes={[".xlsx", ".xlsm", ".csv"]}
        enviar={(arquivo) => importarPonto(arquivo, dataReferencia || undefined)}
        aoConcluir={() => setDataReferencia("")}
        extras={
          <div className="flex flex-col gap-1">
            <label htmlFor="data-referencia" className="text-xs font-medium text-slate-500">
              Data de referência dos saldos <span className="font-normal">(opcional)</span>
            </label>
            <input
              id="data-referencia"
              type="date"
              value={dataReferencia}
              onChange={(e) => setDataReferencia(e.target.value)}
              className="h-9 w-48 rounded-md border border-slate-300 bg-white px-2.5 text-sm text-slate-900 shadow-xs focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 focus:outline-none"
            />
          </div>
        }
        ajuda={(arquivo) =>
          // O relatório do Two não traz a data de emissão: sem data informada, vale a do arquivo.
          !dataReferencia && (
            <p className="-mt-2 text-xs text-slate-500">
              {arquivo
                ? `Em branco, será usada a data do arquivo: ${formatarDia(format(new Date(arquivo.lastModified), "yyyy-MM-dd"))}.`
                : "Em branco, será usada a data de modificação do arquivo."}
            </p>
          )
        }
      />
    </div>
  );
}
