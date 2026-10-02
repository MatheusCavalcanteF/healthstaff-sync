import { redirect } from "next/navigation";

/** A tela inicial é o painel do mês corrente. */
export default function Inicio() {
  redirect("/analises");
}
