/**
 * De qual cabeçalho sai o IP do cliente para o limite de login por IP. Sem
 * aliases: roda direto no `node --test`.
 *
 * Só faz sentido atrás de um proxy reverso confiável, que define o cabeçalho
 * escolhido (`HEALTHSTAFF_CABECALHO_IP`). Sem proxy, qualquer cabeçalho pode
 * ser forjado pelo cliente, e não há IP real no servidor do Next: por isso,
 * sem configuração, o limite por IP fica desligado (vale o por e-mail do backend).
 */

export const CABECALHOS_IP = ["x-real-ip", "x-forwarded-for"] as const;
export type CabecalhoIp = (typeof CABECALHOS_IP)[number];

/** Valor de `HEALTHSTAFF_CABECALHO_IP`: vazio desliga; outro valor que não os aceitos é erro de configuração. */
export function cabecalhoIpConfigurado(valor: string | undefined): CabecalhoIp | null {
  const nome = (valor ?? "").trim().toLowerCase();
  if (!nome) {
    return null;
  }
  if (!(CABECALHOS_IP as readonly string[]).includes(nome)) {
    throw new Error(`HEALTHSTAFF_CABECALHO_IP inválido: "${valor}" (use ${CABECALHOS_IP.join(" ou ")}, ou deixe vazio)`);
  }
  return nome as CabecalhoIp;
}

/**
 * IP lido só do cabeçalho configurado; no `X-Forwarded-For`, o último endereço
 * (o que o proxy acrescentou; os anteriores vieram do cliente). Sem
 * configuração ou sem o cabeçalho, `null`: a tentativa não conta, porque uma
 * chave compartilhada ("desconhecido") deixaria 20 erros de qualquer pessoa
 * bloquearem o login de todos.
 */
export function ipDoCliente(cabecalho: CabecalhoIp | null, ler: (nome: string) => string | null): string | null {
  if (!cabecalho) {
    return null;
  }
  const valor = ler(cabecalho);
  const ip = cabecalho === "x-forwarded-for" ? valor?.split(",").at(-1) : valor;
  return ip?.trim() || null;
}
