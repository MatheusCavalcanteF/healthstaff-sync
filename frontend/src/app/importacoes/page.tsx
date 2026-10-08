import type { Metadata } from "next";
import { Lock } from "lucide-react";

import { EstadoVazio } from "@/components/estado/EstadoVazio";
import { PainelImportacoes } from "@/components/importacoes/PainelImportacoes";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { ROTULO_PERFIL, podeImportar } from "@/lib/auth";
import { lerUsuarioSessao } from "@/lib/sessao";

export const metadata: Metadata = { title: "Importações" };

/** Envio da escala (PDF/Excel) e do relatório de Banco de Horas do Two (Excel/CSV). */
export default async function Importacoes() {
  const usuario = await lerUsuarioSessao();

  return (
    <>
      <CabecalhoPagina
        titulo="Importações"
        subtitulo="Envie a escala do setor e o relatório de Banco de Horas do Two; a análise do mês é refeita a cada envio"
      />
      {/* Só interface: quem barra de fato é o backend (403 ACESSO_NEGADO). */}
      {usuario && !podeImportar(usuario.perfil) ? (
        <EstadoVazio
          icone={Lock}
          titulo="Importação não disponível para o seu perfil"
          descricao={`O perfil ${ROTULO_PERFIL[usuario.perfil]} consulta análises e colaboradores, mas não envia arquivos. Peça a um administrador ou à gestão de RH.`}
        />
      ) : (
        <PainelImportacoes />
      )}
    </>
  );
}
