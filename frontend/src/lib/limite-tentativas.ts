/**
 * Limite de tentativas por chave (ex.: IP) numa janela fixa, na memória do
 * processo do Next. Sem dependências nem aliases: roda direto no `node --test`.
 *
 * A tentativa é reservada antes da chamada ao backend (requisições em paralelo
 * não passam todas de uma vez) e devolvida quando não deve contar, como num
 * login aceito: assim, muita gente entrando pela mesma rede (NAT do hospital)
 * só esbarra no limite se errar a senha.
 */

export interface Reserva {
  permitida: boolean;
  /** Quando recusada: segundos até a janela da chave reabrir (no mínimo 1). */
  esperaSegundos: number;
}

interface Janela {
  inicio: number;
  tentativas: number;
}

/** Acima disso, as janelas vencidas são descartadas (memória limitada). */
const MAXIMO_CHAVES = 10_000;

export class LimiteTentativas {
  private readonly janelas = new Map<string, Janela>();
  private readonly maximo: number;
  private readonly janelaMs: number;
  private readonly agora: () => number;

  constructor(maximo: number, janelaMs: number, agora: () => number = Date.now) {
    this.maximo = maximo;
    this.janelaMs = janelaMs;
    this.agora = agora;
  }

  /** Conta uma tentativa para a chave; recusa (sem contar) se ela já esgotou a janela. */
  reservar(chave: string): Reserva {
    const agora = this.agora();
    this.descartarVencidas(agora);
    let janela = this.janelas.get(chave);
    if (!janela || agora - janela.inicio >= this.janelaMs) {
      janela = { inicio: agora, tentativas: 0 };
      this.janelas.set(chave, janela);
    }
    if (janela.tentativas >= this.maximo) {
      const restanteMs = janela.inicio + this.janelaMs - agora;
      return { permitida: false, esperaSegundos: Math.max(1, Math.ceil(restanteMs / 1000)) };
    }
    janela.tentativas++;
    return { permitida: true, esperaSegundos: 0 };
  }

  /** Desfaz uma reserva que não deve contar (login aceito, backend fora do ar). */
  devolver(chave: string): void {
    const janela = this.janelas.get(chave);
    if (janela && janela.tentativas > 0) {
      janela.tentativas--;
    }
  }

  private descartarVencidas(agora: number): void {
    if (this.janelas.size <= MAXIMO_CHAVES) {
      return;
    }
    for (const [chave, janela] of this.janelas) {
      if (agora - janela.inicio >= this.janelaMs) {
        this.janelas.delete(chave);
      }
    }
  }
}
