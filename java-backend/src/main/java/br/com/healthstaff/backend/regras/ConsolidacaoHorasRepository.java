package br.com.healthstaff.backend.regras;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ConsolidacaoHorasRepository extends JpaRepository<ConsolidacaoHoras, Long> {

	/** Somas e contagens da consolidação de uma análise, calculadas no banco. */
	@Query("""
			select new br.com.healthstaff.backend.regras.ConsolidacaoHorasRepository$Totais(
			    coalesce(sum(c.minutosEscalados), 0),
			    coalesce(sum(c.minutosCreditoFg), 0),
			    coalesce(sum(c.minutosCumpridos), 0),
			    coalesce(sum(c.minutosEsperados), 0),
			    coalesce(sum(c.diferencaMinutos), 0),
			    coalesce(sum(case when c.minutosEsperados is not null and c.minutosCumpridos < c.minutosEsperados then 1 else 0 end), 0),
			    coalesce(sum(case when c.minutosEsperados is not null and c.minutosCumpridos = c.minutosEsperados then 1 else 0 end), 0),
			    coalesce(sum(case when c.minutosEsperados is not null and c.minutosCumpridos > c.minutosEsperados then 1 else 0 end), 0),
			    coalesce(sum(case when c.minutosEsperados is null then 1 else 0 end), 0),
			    coalesce(sum(case when c.saldoMinutos is not null then 1 else 0 end), 0),
			    coalesce(sum(case when c.saldoMinutos >= :saldoAlto then 1 else 0 end), 0),
			    coalesce(sum(case when c.saldoMinutos <= :saldoBaixo then 1 else 0 end), 0),
			    coalesce(sum(c.saldoMinutos), 0),
			    coalesce(sum(c.saldoProjetadoMinutos), 0))
			from ConsolidacaoHoras c
			where c.analiseId = :analiseId""")
	Totais totais(Long analiseId, int saldoAlto, int saldoBaixo);

	record Totais(long minutosEscalados, long minutosCreditoFg, long minutosCumpridos, long minutosEsperados,
			long diferencaMinutos, long abaixo, long naCarga, long acima, long semCarga, long comSaldo,
			long saldoAlto, long saldoBaixo, long saldoMinutos, long saldoProjetadoMinutos) {
	}
}
