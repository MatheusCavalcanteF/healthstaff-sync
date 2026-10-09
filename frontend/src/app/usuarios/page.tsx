import type { Metadata } from "next";
import { Lock } from "lucide-react";

import { ErroCarregamento } from "@/components/estado/ErroCarregamento";
import { EstadoVazio } from "@/components/estado/EstadoVazio";
import { CabecalhoPagina } from "@/components/layout/CabecalhoPagina";
import { PainelUsuarios } from "@/components/usuarios/PainelUsuarios";
import { ApiError } from "@/lib/api/client";
import { listarUsuarios } from "@/lib/api/endpoints";
import { lerUsuarioSessao } from "@/lib/sessao";
import type { Usuario } from "@/types/api";

export const metadata: Metadata = { title: "Usuários" };

type Carregamento = { estado: "ok"; usuarios: Usuario[] } | { estado: "erro"; erro: ApiError };

async function carregar(): Promise<Carregamento> {
  try {
    return { estado: "ok", usuarios: await listarUsuarios() };
  } catch (erro) {
    if (erro instanceof ApiError) {
      return { estado: "erro", erro };
    }
    throw erro;
  }
}

const TITULO = "Usuários";
const SUBTITULO = "Quem acessa o HealthStaff Sync e com qual perfil";

/** Gestão de usuários (só ADMIN; a API recusa os demais com 403). */
export default async function Usuarios() {
  const sessao = await lerUsuarioSessao();

  if (sessao?.perfil !== "ADMIN") {
    return (
      <>
        <CabecalhoPagina titulo={TITULO} subtitulo={SUBTITULO} />
        <EstadoVazio
          icone={Lock}
          titulo="Área restrita a administradores"
          descricao="Só o perfil Administrador cadastra e altera usuários."
        />
      </>
    );
  }

  const resultado = await carregar();
  if (resultado.estado === "erro") {
    return (
      <>
        <CabecalhoPagina titulo={TITULO} subtitulo={SUBTITULO} />
        <ErroCarregamento erro={resultado.erro} oQue="os usuários" />
      </>
    );
  }

  return <PainelUsuarios titulo={TITULO} subtitulo={SUBTITULO} usuarios={resultado.usuarios} idLogado={sessao.id} />;
}
