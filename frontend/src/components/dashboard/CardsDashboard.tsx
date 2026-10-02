import Link from "next/link";
import { ChartNoAxesColumn, PiggyBank, TriangleAlert, UserRoundX } from "lucide-react";

import { KpiCard } from "@/components/dashboard/KpiCard";
import { ROTULO_GRAVIDADE, formatarHoras, formatarNumero, formatarPercentual } from "@/lib/formato";
import { cn } from "@/lib/utils";
import type { Competencia, Gravidade, ResumoAnalise } from "@/types/api";

const ORDEM_GRAVIDADE: Gravidade[] = ["CRITICA", "ALTA", "MEDIA"];

const COR_GRAVIDADE: Record<Gravidade, string> = {
  CRITICA: "bg-red-50 text-red-700 ring-red-200 hover:bg-red-100",
  ALTA: "bg-orange-50 text-orange-700 ring-orange-200 hover:bg-orange-100",
  MEDIA: "bg-amber-50 text-amber-700 ring-amber-200 hover:bg-amber-100",
};

/** Total de inconformidades e quebra por gravidade (cada uma leva à lista filtrada). */
export function CardInconformidades({ resumo, competencia }: { resumo: ResumoAnalise; competencia: Competencia }) {
  const { total, porGravidade } = resumo.inconformidades;
  return (
    <KpiCard
      titulo="Inconformidades"
      icone={TriangleAlert}
      tom={total > 0 ? "alerta" : "sucesso"}
      valor={formatarNumero(total)}
      descricao={total === 0 ? "Nenhuma violação encontrada no mês" : "alertas na análise vigente"}
    >
      <ul className="flex flex-wrap gap-2" aria-label="Por gravidade">
        {ORDEM_GRAVIDADE.map((gravidade) => (
          <li key={gravidade}>
            <Link
              href={`/analises/${competencia}/inconformidades?gravidade=${gravidade}`}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ring-1 ring-inset transition-colors",
                COR_GRAVIDADE[gravidade],
                porGravidade[gravidade] === 0 && "opacity-50",
              )}
            >
              {ROTULO_GRAVIDADE[gravidade]}
              <span className="tabular-nums font-semibold">{porGravidade[gravidade]}</span>
            </Link>
          </li>
        ))}
      </ul>
    </KpiCard>
  );
}

export function CardColaboradoresAfetados({ resumo }: { resumo: ResumoAnalise }) {
  const afetados = resumo.inconformidades.colaboradoresAfetados;
  const analisados = resumo.colaboradoresAnalisados;
  return (
    <KpiCard
      titulo="Colaboradores afetados"
      icone={UserRoundX}
      tom={afetados > 0 ? "alerta" : "sucesso"}
      valor={formatarNumero(afetados)}
      descricao={`de ${formatarNumero(analisados)} analisados (${formatarPercentual(afetados, analisados)})`}
    />
  );
}

/** Soma dos saldos do Two de quem está na escala e projeção para o fim do mês. */
export function CardBancoHoras({ resumo }: { resumo: ResumoAnalise }) {
  const h = resumo.horas;
  return (
    <KpiCard
      titulo="Saldo de banco de horas"
      icone={PiggyBank}
      valor={formatarHoras(h.saldoMinutos, { comSinal: true })}
      descricao={`soma de ${formatarNumero(h.colaboradoresComSaldo)} colaboradores com saldo no Two`}
    >
      <dl className="grid grid-cols-3 gap-2 text-sm">
        <div>
          <dt className="text-xs text-slate-500">Projetado</dt>
          <dd className="font-medium tabular-nums text-slate-900">
            {formatarHoras(h.saldoProjetadoMinutos, { comSinal: true })}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Saldo alto</dt>
          <dd className="font-medium tabular-nums text-slate-900">{formatarNumero(h.colaboradoresSaldoAlto)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Saldo baixo</dt>
          <dd className="font-medium tabular-nums text-slate-900">{formatarNumero(h.colaboradoresSaldoBaixo)}</dd>
        </div>
      </dl>
    </KpiCard>
  );
}

const FAIXAS = [
  { chave: "colaboradoresAbaixoDaCarga", rotulo: "Abaixo", cor: "bg-amber-400" },
  { chave: "colaboradoresNaCarga", rotulo: "Na carga", cor: "bg-emerald-500" },
  { chave: "colaboradoresAcimaDaCarga", rotulo: "Acima", cor: "bg-sky-500" },
  { chave: "colaboradoresSemCargaDefinida", rotulo: "Sem carga", cor: "bg-slate-300" },
] as const;

/**
 * Quantos colaboradores ficaram abaixo, na ou acima da carga contratual
 * (carga cumprida = plantão + crédito de FG).
 */
export function CardDistribuicaoCarga({ resumo }: { resumo: ResumoAnalise }) {
  const h = resumo.horas;
  const faixas = FAIXAS.map((f) => ({ ...f, quantidade: h[f.chave] })).filter(
    (f) => f.chave !== "colaboradoresSemCargaDefinida" || f.quantidade > 0,
  );
  const total = faixas.reduce((soma, f) => soma + f.quantidade, 0);

  return (
    <KpiCard
      titulo="Distribuição de carga horária"
      icone={ChartNoAxesColumn}
      valor={`${formatarHoras(h.minutosCumpridos)}`}
      descricao={`cumpridas de ${formatarHoras(h.minutosEsperados)} previstas (${formatarHoras(h.diferencaMinutos, { comSinal: true })})`}
    >
      <div
        className="flex h-2.5 overflow-hidden rounded-full bg-slate-100"
        role="img"
        aria-label={faixas.map((f) => `${f.rotulo}: ${f.quantidade}`).join(", ")}
      >
        {faixas.map((f) =>
          f.quantidade > 0 ? (
            <div key={f.chave} className={f.cor} style={{ width: `${(f.quantidade / total) * 100}%` }} />
          ) : null,
        )}
      </div>
      <ul className="mt-3 grid grid-cols-3 gap-2 text-sm">
        {faixas.map((f) => (
          <li key={f.chave}>
            <span className="flex items-center gap-1.5 text-xs text-slate-500">
              <span className={cn("size-2 rounded-full", f.cor)} aria-hidden />
              {f.rotulo}
            </span>
            <span className="font-medium tabular-nums text-slate-900">
              {formatarNumero(f.quantidade)}{" "}
              <span className="text-xs font-normal text-slate-400">{formatarPercentual(f.quantidade, total)}</span>
            </span>
          </li>
        ))}
      </ul>
    </KpiCard>
  );
}
