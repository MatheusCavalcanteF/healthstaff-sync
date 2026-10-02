import { redirect } from "next/navigation";
import { connection } from "next/server";

import { competenciaAtual } from "@/lib/competencia";

/**
 * Redireciona para a competência corrente. `connection()` garante que o mês é
 * calculado a cada requisição, não congelado no momento do build.
 *
 * `?para=inconformidades` leva à lista de alertas do mês corrente.
 */
export default async function AnalisesAtual(props: PageProps<"/analises">) {
  await connection();
  const { para } = await props.searchParams;
  const competencia = competenciaAtual();
  redirect(para === "inconformidades" ? `/analises/${competencia}/inconformidades` : `/analises/${competencia}`);
}
