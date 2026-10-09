/**
 * Tipos da API do java-backend (`/api/v1`), espelhando os records Java.
 *
 * Formatos de data como trafegam no JSON (Jackson):
 * - `DataISO`      LocalDate      "2026-10-01"
 * - `DataHoraISO`  LocalDateTime  "2026-10-01T19:00:00"  (hora local, sem fuso)
 * - `InstanteISO`  Instant        "2026-10-01T22:15:30.123Z"
 * - `Competencia`  YearMonth      "2026-10"
 *
 * Campos que o backend pode enviar como `null` estão tipados com `| null`.
 */

export type DataISO = string;
export type DataHoraISO = string;
export type InstanteISO = string;
export type Competencia = string;
export type UUID = string;

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

export type TipoInconformidade =
  | "INTERJORNADA"
  | "SOBREPOSICAO_PLANTOES"
  | "JORNADA_EXCESSIVA"
  | "SALDO_POSITIVO_ESCALA_ACIMA"
  | "SALDO_NEGATIVO_ESCALA_ABAIXO"
  | "FOLGA_BH_SEM_SALDO";

export type Gravidade = "CRITICA" | "ALTA" | "MEDIA";

export type GatilhoAnalise = "IMPORTACAO_ESCALA" | "IMPORTACAO_PONTO" | "MANUAL";

export type Vinculo = "OFICIAL" | "PROVISORIO";

export type CategoriaProfissional =
  | "ENFERMEIRO"
  | "TECNICO_ENFERMAGEM"
  | "ADMINISTRATIVO"
  | "OUTRO";

export type TipoDocumento =
  | "ESCALA_PDF"
  | "ESCALA_EXCEL"
  | "PONTO_EXCEL"
  | "PONTO_CSV";

// ---------------------------------------------------------------------------
// Análises — GET /analises/{AAAA-MM}
// ---------------------------------------------------------------------------

/** AnaliseResumo.Alertas: todas as chaves de gravidade e tipo vêm preenchidas (inclusive 0). */
export interface ResumoAlertas {
  total: number;
  colaboradoresAfetados: number;
  porGravidade: Record<Gravidade, number>;
  porTipo: Record<TipoInconformidade, number>;
}

/** AnaliseResumo.Horas: minutos. "Cumpridos" = plantão + crédito de FG. */
export interface ResumoHoras {
  minutosEscalados: number;
  minutosCreditoFg: number;
  minutosCumpridos: number;
  minutosEsperados: number;
  diferencaMinutos: number;
  colaboradoresAbaixoDaCarga: number;
  colaboradoresNaCarga: number;
  colaboradoresAcimaDaCarga: number;
  colaboradoresSemCargaDefinida: number;
  colaboradoresComSaldo: number;
  colaboradoresSaldoAlto: number;
  colaboradoresSaldoBaixo: number;
  saldoMinutos: number;
  saldoProjetadoMinutos: number;
}

/** GET /analises/{AAAA-MM} — painel do mês (record Java `AnaliseResumo`). */
export interface ResumoAnalise {
  id: number;
  competencia: Competencia;
  executadaEm: InstanteISO;
  gatilho: GatilhoAnalise;
  importacaoId: UUID | null;
  colaboradoresAnalisados: number;
  inconformidades: ResumoAlertas;
  horas: ResumoHoras;
}

/** POST /analises/{AAAA-MM} — reprocessamento (record Java `MotorRegrasService.ResumoAnalise`). */
export interface ResumoReprocessamento {
  analiseId: number;
  competencia: Competencia;
  gatilho: GatilhoAnalise;
  colaboradoresAnalisados: number;
  totalInconformidades: number;
  /** Só os tipos encontrados (sem chaves com zero). */
  porTipo: Partial<Record<TipoInconformidade, number>>;
}

// ---------------------------------------------------------------------------
// Inconformidades — GET /analises/{AAAA-MM}/inconformidades
// ---------------------------------------------------------------------------

export interface PessoaInconformidade {
  id: number;
  matricula: string | null;
  nome: string;
  provisorio: boolean;
}

export interface PlantaoResumo {
  id: number;
  data: DataISO;
  inicio: DataHoraISO | null;
  fim: DataHoraISO | null;
  turno: string | null;
  codigoOriginal: string;
  setor: string;
}

/** Alerta de regra de negócio (record Java `InconformidadeResposta`). */
export interface Inconformidade {
  id: number;
  tipo: TipoInconformidade;
  gravidade: Gravidade;
  data: DataISO;
  colaborador: PessoaInconformidade;
  /** Nulo nos alertas de saldo (não há plantão específico). */
  plantao: PlantaoResumo | null;
  plantaoRelacionado: PlantaoResumo | null;
  valorMinutos: number | null;
  limiteMinutos: number | null;
  descricao: string;
}

export interface FiltrosInconformidade {
  tipo?: TipoInconformidade;
  gravidade?: Gravidade;
  colaboradorId?: number;
}

// ---------------------------------------------------------------------------
// Colaboradores — GET /colaboradores
// ---------------------------------------------------------------------------

export interface SaldoBancoHoras {
  minutos: number;
  dataReferencia: DataISO;
}

/** Record Java `ColaboradorResposta`. */
export interface Colaborador {
  id: number;
  matricula: string | null;
  nome: string;
  vinculo: Vinculo;
  cargo: string | null;
  /** Nula quando o cargo é desconhecido (quem só aparece no relatório do Two). */
  categoria: CategoriaProfissional | null;
  jornada: string | null;
  registroConselho: string | null;
  /** Nulo para quem não está no Two. */
  saldoBancoHoras: SaldoBancoHoras | null;
}

export interface FiltrosColaborador {
  vinculo?: Vinculo;
  busca?: string;
  /** Começa em 0. */
  pagina?: number;
  /** De 1 a 200 (padrão 50). */
  tamanho?: number;
}

/** Record Java `web.Pagina`. */
export interface Pagina<T> {
  conteudo: T[];
  pagina: number;
  tamanho: number;
  totalElementos: number;
  totalPaginas: number;
}

// ---------------------------------------------------------------------------
// Importações — POST /importacoes/escalas | /importacoes/ponto, GET /importacoes/{id}
// ---------------------------------------------------------------------------

export interface VersaoEscala {
  setor: string;
  competencia: DataISO;
  versao: number;
  vigente: boolean;
  marcacoes: number;
}

/** Inconsistência de extração (distinta das inconformidades de regra de negócio). */
export interface InconsistenciaImportacao {
  codigo: string;
  severidade: "INFO" | "AVISO" | "ERRO";
  mensagem: string;
  aba: string | null;
  linha: number | null;
  pagina: number | null;
  setor: string | null;
  matricula: string | null;
  nomeColaborador: string | null;
  data: DataISO | null;
  valorBruto: string | null;
}

/** Record Java `ImportacaoResposta`. 201 = arquivo novo; 200 com `duplicada` = já importado. */
export interface ImportacaoResponse {
  id: UUID;
  tipoDocumento: TipoDocumento;
  nomeArquivo: string;
  duplicada: boolean;
  periodoInicio: DataISO;
  periodoFim: DataISO;
  importadoEm: InstanteISO;
  totalColaboradores: number;
  totalPlantoes: number;
  possuiErros: boolean;
  /** Versões de escala criadas (vazio no banco de horas). */
  escalas: VersaoEscala[];
  /** Saldos gravados (zero nas escalas). */
  saldos: number;
  inconsistencias: InconsistenciaImportacao[];
}

// ---------------------------------------------------------------------------
// Erros — Problem Details (RFC 9457)
// ---------------------------------------------------------------------------

/** Códigos estáveis em `ProblemDetail.codigo` (lista aberta: o extrator pode acrescentar). */
export type CodigoErro =
  | "ARQUIVO_AUSENTE"
  | "ARQUIVO_INVALIDO"
  | "ARQUIVO_MUITO_GRANDE"
  | "PARAMETRO_INVALIDO"
  | "IMPORTACAO_NAO_ENCONTRADA"
  | "ANALISE_NAO_ENCONTRADA"
  | "SEM_ESCALA_VIGENTE"
  | "EXTRATOR_INDISPONIVEL"
  | "TIPO_INCOMPATIVEL"
  | "FORMATO_NAO_RECONHECIDO"
  | "EXTRACAO_FALHOU"
  /** Sem token, ou token expirado/inválido: a tela manda para o login. */
  | "NAO_AUTENTICADO"
  /** O perfil do usuário não permite a operação (ex.: AUDITOR importando). */
  | "ACESSO_NEGADO"
  /** Login recusado (mesma resposta para e-mail inexistente, senha errada ou usuário inativo). */
  | "CREDENCIAIS_INVALIDAS"
  /** Gestão de usuários (409/404). */
  | "EMAIL_JA_CADASTRADO"
  | "ULTIMO_ADMIN"
  | "ALTERACAO_PROPRIA_BLOQUEADA"
  | "USUARIO_NAO_ENCONTRADO"
  /** Gerado no próprio frontend quando não há resposta do servidor. */
  | "SEM_CONEXAO"
  | (string & {});

/** Corpo de erro do backend (`application/problem+json`). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  codigo?: CodigoErro;
  /** Presente quando o erro se refere a um arquivo enviado. */
  arquivo?: string;
}

// ---------------------------------------------------------------------------
// Autenticação e usuários — POST /auth/login, GET /auth/eu, /usuarios (só ADMIN)
// ---------------------------------------------------------------------------

export type Perfil = "ADMIN" | "GESTOR_RH" | "AUDITOR";

/** UsuarioResponse */
export interface Usuario {
  id: UUID;
  nome: string;
  email: string;
  perfil: Perfil;
  ativo: boolean;
  dataCriacao: InstanteISO;
}

/** NovoUsuarioRequest */
export interface NovoUsuario {
  nome: string;
  email: string;
  senha: string;
  perfil: Perfil;
}

/** AtualizarUsuarioRequest: `senha` vazia ou ausente mantém a atual. */
export interface AtualizacaoUsuario {
  nome: string;
  email: string;
  perfil: Perfil;
  senha?: string;
}

/** TokenResponse */
export interface TokenResponse {
  token: string;
  tipo: "Bearer";
  expiraEm: InstanteISO;
  usuario: Usuario;
}
