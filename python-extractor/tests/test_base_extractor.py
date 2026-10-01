import hashlib
import json
import logging
import re
from abc import ABC
from datetime import date, datetime

import pytest
from pydantic import ValidationError

from extractors import (
    BaseExtractor,
    ContextoExtracao,
    ErroExtracao,
    ExtractorRegistry,
    ExtratorNaoEncontrado,
)
from models import (
    CodigoInconsistencia,
    Colaborador,
    Inconsistencia,
    LoteImportacao,
    Origem,
    Plantao,
    Setor,
    Severidade,
    TipoDocumento,
    TipoMarcacao,
    Turno,
)

# ---------------------------------------------------------------------------
# Extratores de teste
# ---------------------------------------------------------------------------


def _texto(pdf) -> str:
    return pdf.pages[0].extract_text() or ""


class EscalaFakeExtractor(BaseExtractor):
    """Layout fictício:

    ESCALA FAKE
    PERIODO 2026-10-01 2026-10-31
    SETOR UTI
    COLAB <matricula|-> <nome> <AAAA-MM-DD> <codigo>
    """

    nome = "escala-fake"
    versao = "1.0.0"
    cabecalho = "ESCALA FAKE"

    def pode_processar(self, pdf) -> bool:
        return _texto(pdf).startswith(self.cabecalho)

    def extrair_periodo(self, pdf, ctx):
        m = re.search(r"PERIODO (\S+) (\S+)", _texto(pdf))
        if not m:
            raise ErroExtracao("período não encontrado")
        return date.fromisoformat(m[1]), date.fromisoformat(m[2])

    def extrair_setores(self, pdf, ctx):
        setor = Setor(nome=re.search(r"SETOR (.+)", _texto(pdf))[1])
        for m in re.finditer(r"COLAB (\S+) (\w+) (\S+) (\w+)", _texto(pdf)):
            matricula, nome, dia, codigo = m.groups()
            dia = date.fromisoformat(dia)
            if matricula == "-":
                matricula = None
                ctx.registrar(
                    CodigoInconsistencia.COLABORADOR_SEM_MATRICULA,
                    f"{nome} sem matrícula",
                    nome_colaborador=nome,
                    pagina=1,
                )
            if codigo == "M":
                plantao = Plantao(
                    data=dia,
                    tipo=TipoMarcacao.PLANTAO,
                    codigo_original=codigo,
                    turno=Turno.MANHA,
                    inicio=datetime.combine(dia, datetime.min.time()).replace(hour=7),
                    fim=datetime.combine(dia, datetime.min.time()).replace(hour=13),
                )
            else:
                plantao = Plantao(
                    data=dia, tipo=TipoMarcacao.FOLGA, codigo_original=codigo
                )
            setor.colaboradores.append(
                Colaborador(matricula=matricula, nome=nome, plantoes=[plantao])
            )
        return [setor]


class OutroLayoutExtractor(EscalaFakeExtractor):
    nome = "outro-layout"
    cabecalho = "OUTRO LAYOUT"


class GenericoExtractor(EscalaFakeExtractor):
    """Fallback que aceita qualquer PDF."""

    nome = "generico"

    def pode_processar(self, pdf) -> bool:
        return True


class QuebradoExtractor(EscalaFakeExtractor):
    nome = "quebrado"

    def pode_processar(self, pdf) -> bool:
        raise RuntimeError("bug no detector")


LINHAS_VALIDAS = [
    "ESCALA FAKE",
    "PERIODO 2026-10-01 2026-10-31",
    "SETOR UTI",
    "COLAB 123 Maria 2026-10-01 M",
    "COLAB - Joao 2026-10-02 F",
]


# ---------------------------------------------------------------------------
# Fábricas de modelos
# ---------------------------------------------------------------------------


def nova_origem(**kw) -> Origem:
    dados = dict(
        tipo_documento=TipoDocumento.ESCALA_PDF,
        nome_arquivo="uti.pdf",
        hash_sha256="a" * 64,
        tamanho_bytes=100,
        total_paginas=1,
        extrator="teste",
        versao_extrator="1",
    )
    return Origem(**(dados | kw))


def novo_plantao(**kw) -> Plantao:
    dados = dict(
        data=date(2026, 10, 1),
        tipo=TipoMarcacao.PLANTAO,
        codigo_original="SN",
        turno=Turno.NOTURNO_12H,
        inicio=datetime(2026, 10, 1, 19),
        fim=datetime(2026, 10, 2, 7),
    )
    return Plantao(**(dados | kw))


def novo_lote(**kw) -> LoteImportacao:
    colaborador = Colaborador(
        matricula="123",
        nome="  José   da Conceição ",
        folguista=True,
        plantoes=[
            novo_plantao(),
            Plantao(data=date(2026, 10, 3), tipo="FOLGA", codigo_original="F"),
        ],
    )
    dados = dict(
        origem=nova_origem(),
        periodo_inicio=date(2026, 10, 1),
        periodo_fim=date(2026, 10, 31),
        setores=[Setor(codigo="UTI-A", nome="UTI Adulto", colaboradores=[colaborador])],
        inconsistencias=[
            Inconsistencia(
                codigo=CodigoInconsistencia.CELULA_ILEGIVEL,
                severidade=Severidade.AVISO,
                mensagem="célula borrada",
                pagina=1,
                valor_bruto="?#",
            )
        ],
    )
    return LoteImportacao(**(dados | kw))


def _todas_as_chaves(obj) -> set[str]:
    if isinstance(obj, dict):
        return set(obj) | {k for v in obj.values() for k in _todas_as_chaves(v)}
    if isinstance(obj, list):
        return {k for item in obj for k in _todas_as_chaves(item)}
    return set()


# ---------------------------------------------------------------------------
# 1. Serialização do LoteImportacao e contrato JSON
# ---------------------------------------------------------------------------


class TestContratoJson:
    def test_serializa_chaves_em_camel_case(self):
        dados = json.loads(novo_lote().to_json())
        chaves = _todas_as_chaves(dados)

        assert {"idLote", "versaoContrato", "periodoInicio", "hashSha256",
                "codigoOriginal", "nomeNormalizado", "duracaoHoras",
                "totalPlantoes", "possuiErros", "valorBruto"} <= chaves
        assert not [c for c in chaves if "_" in c]

    def test_tipos_serializados_no_formato_esperado_pelo_backend(self):
        dados = json.loads(novo_lote().to_json())
        plantao = dados["setores"][0]["colaboradores"][0]["plantoes"][0]

        assert dados["versaoContrato"] == "1.0"
        assert dados["periodoInicio"] == "2026-10-01"
        assert plantao["inicio"] == "2026-10-01T19:00:00"
        assert plantao["tipo"] == "PLANTAO"
        assert plantao["turno"] == "NOTURNO_12H"
        assert dados["origem"]["tipoDocumento"] == "ESCALA_PDF"
        assert dados["inconsistencias"][0]["severidade"] == "AVISO"

    def test_campos_calculados(self):
        dados = json.loads(novo_lote().to_json())
        colaborador = dados["setores"][0]["colaboradores"][0]

        assert colaborador["nome"] == "José da Conceição"
        assert colaborador["nomeNormalizado"] == "JOSE DA CONCEICAO"
        assert [p["duracaoHoras"] for p in colaborador["plantoes"]] == [12.0, 0.0]
        assert dados["totalColaboradores"] == 1
        assert dados["totalPlantoes"] == 1  # folga não conta
        assert dados["possuiErros"] is False

    def test_possui_erros_quando_ha_inconsistencia_de_severidade_erro(self):
        lote = novo_lote(
            inconsistencias=[
                Inconsistencia(
                    codigo="DATA_INVALIDA", severidade="ERRO", mensagem="dia 32"
                )
            ]
        )
        assert lote.possui_erros is True

    def test_round_trip_json(self):
        lote = novo_lote()
        recarregado = LoteImportacao.model_validate_json(lote.to_json())
        assert recarregado == lote

    def test_aceita_entrada_em_snake_case(self):
        lote = novo_lote()
        recarregado = LoteImportacao.model_validate(lote.model_dump(by_alias=False))
        assert recarregado == lote

    def test_rejeita_campo_desconhecido(self):
        dados = json.loads(novo_lote().to_json())
        dados["campoInventado"] = 1
        with pytest.raises(ValidationError, match="campoInventado"):
            LoteImportacao.model_validate(dados)

    def test_rejeita_versao_de_contrato_diferente(self):
        dados = json.loads(novo_lote().to_json())
        dados["versaoContrato"] = "2.0"
        with pytest.raises(ValidationError):
            LoteImportacao.model_validate(dados)

    def test_rejeita_periodo_invertido(self):
        with pytest.raises(ValidationError, match="periodo_fim"):
            novo_lote(periodo_inicio=date(2026, 10, 31), periodo_fim=date(2026, 10, 1))

    def test_rejeita_plantao_fora_do_periodo(self):
        with pytest.raises(ValidationError, match="fora do período"):
            novo_lote(periodo_inicio=date(2026, 10, 2))

    def test_rejeita_hash_invalido(self):
        with pytest.raises(ValidationError):
            nova_origem(hash_sha256="xyz")

    def test_matricula_vazia_vira_none(self):
        assert Colaborador(nome="Ana", matricula="  ").matricula is None

    def test_nome_vazio_rejeitado(self):
        with pytest.raises(ValidationError):
            Colaborador(nome="   ")


class TestPlantao:
    def test_plantao_noturno_atravessa_meia_noite(self):
        assert novo_plantao().duracao_horas == 12.0

    def test_plantao_24h_e_o_limite(self):
        plantao = novo_plantao(fim=datetime(2026, 10, 2, 19))
        assert plantao.duracao_horas == 24.0

    @pytest.mark.parametrize(
        ("campos", "mensagem"),
        [
            ({"inicio": None}, "exige 'inicio' e 'fim'"),
            ({"fim": None}, "exige 'inicio' e 'fim'"),
            ({"fim": datetime(2026, 10, 1, 19)}, "posterior a 'inicio'"),
            ({"fim": datetime(2026, 10, 1, 18)}, "posterior a 'inicio'"),
            ({"fim": datetime(2026, 10, 2, 19, 1)}, "superior a 24h"),
            ({"data": date(2026, 9, 30)}, "mesma data"),
        ],
    )
    def test_plantao_com_horarios_invalidos(self, campos, mensagem):
        with pytest.raises(ValidationError, match=mensagem):
            novo_plantao(**campos)

    @pytest.mark.parametrize("tipo", [t for t in TipoMarcacao if t != TipoMarcacao.PLANTAO])
    def test_marcacao_sem_trabalho_nao_aceita_horarios(self, tipo):
        with pytest.raises(ValidationError, match="não deve ter horários"):
            novo_plantao(tipo=tipo)

    def test_folga_sem_horarios_valida(self):
        folga = Plantao(data=date(2026, 10, 1), tipo="FOLGA", codigo_original="F")
        assert folga.duracao_horas == 0.0


# ---------------------------------------------------------------------------
# 2. BaseExtractor
# ---------------------------------------------------------------------------


class TestBaseExtractor:
    def test_gera_lote_completo(self, criar_pdf):
        caminho = criar_pdf(LINHAS_VALIDAS, nome="uti_out.pdf")

        lote = EscalaFakeExtractor().extrair(caminho)

        assert isinstance(lote, LoteImportacao)
        assert (lote.periodo_inicio, lote.periodo_fim) == (
            date(2026, 10, 1),
            date(2026, 10, 31),
        )
        assert lote.setores[0].nome == "UTI"
        assert [c.nome for c in lote.setores[0].colaboradores] == ["Maria", "Joao"]
        assert lote.total_plantoes == 1

    def test_origem_preenchida_a_partir_do_arquivo(self, criar_pdf):
        caminho = criar_pdf(LINHAS_VALIDAS, nome="uti_out.pdf", paginas_extras=[["p2"]])
        conteudo = caminho.read_bytes()

        origem = EscalaFakeExtractor().extrair(caminho).origem

        assert origem.tipo_documento is TipoDocumento.ESCALA_PDF
        assert origem.nome_arquivo == "uti_out.pdf"
        assert origem.hash_sha256 == hashlib.sha256(conteudo).hexdigest()
        assert origem.tamanho_bytes == len(conteudo)
        assert origem.total_paginas == 2
        assert (origem.extrator, origem.versao_extrator) == ("escala-fake", "1.0.0")

    def test_aceita_caminho_como_string(self, criar_pdf):
        lote = EscalaFakeExtractor().extrair(str(criar_pdf(LINHAS_VALIDAS)))
        assert lote.origem.nome_arquivo == "escala.pdf"

    def test_inconsistencias_registradas_entram_no_lote(self, criar_pdf):
        lote = EscalaFakeExtractor().extrair(criar_pdf(LINHAS_VALIDAS))

        assert len(lote.inconsistencias) == 1
        inc = lote.inconsistencias[0]
        assert inc.codigo is CodigoInconsistencia.COLABORADOR_SEM_MATRICULA
        assert inc.severidade is Severidade.AVISO
        assert inc.nome_colaborador == "Joao"
        assert lote.setores[0].colaboradores[1].matricula is None

    def test_instancia_reutilizavel_sem_vazar_inconsistencias(self, criar_pdf):
        extrator = EscalaFakeExtractor()
        com_problema = criar_pdf(LINHAS_VALIDAS, nome="a.pdf")
        sem_problema = criar_pdf(LINHAS_VALIDAS[:4], nome="b.pdf")

        assert len(extrator.extrair(com_problema).inconsistencias) == 1
        assert extrator.extrair(sem_problema).inconsistencias == []

    def test_lote_gerado_serializa_e_recarrega(self, criar_pdf):
        lote = EscalaFakeExtractor().extrair(criar_pdf(LINHAS_VALIDAS))
        assert LoteImportacao.model_validate_json(lote.to_json()) == lote

    def test_arquivo_inexistente(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            EscalaFakeExtractor().extrair(tmp_path / "nao_existe.pdf")

    def test_diretorio_nao_e_aceito(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            EscalaFakeExtractor().extrair(tmp_path)

    def test_layout_nao_reconhecido(self, criar_pdf):
        caminho = criar_pdf(["OUTRO LAYOUT", "PERIODO 2026-10-01 2026-10-31"])
        with pytest.raises(ErroExtracao, match="não reconhece o layout"):
            EscalaFakeExtractor().extrair(caminho)

    def test_erro_na_etapa_de_periodo_propaga(self, criar_pdf):
        caminho = criar_pdf(["ESCALA FAKE", "SETOR UTI"])
        with pytest.raises(ErroExtracao, match="período não encontrado"):
            EscalaFakeExtractor().extrair(caminho)

    def test_dados_inconsistentes_viram_validation_error(self, criar_pdf):
        caminho = criar_pdf(
            ["ESCALA FAKE", "PERIODO 2026-10-01 2026-10-31", "SETOR UTI",
             "COLAB 1 Ana 2026-11-05 M"]
        )
        with pytest.raises(ValidationError, match="fora do período"):
            EscalaFakeExtractor().extrair(caminho)

    def test_subclasse_sem_metodos_abstratos_nao_instancia(self):
        class Incompleto(BaseExtractor):
            nome = "incompleto"
            versao = "1"

        with pytest.raises(TypeError, match="abstract"):
            Incompleto()

    @pytest.mark.parametrize("faltando", ["nome", "versao"])
    def test_subclasse_concreta_exige_nome_e_versao(self, faltando):
        atributos = {"nome": "x", "versao": "1"}
        del atributos[faltando]
        with pytest.raises(TypeError, match=faltando):
            type("SemAtributo", (BaseExtractor,), atributos)

    def test_subclasse_abstrata_intermediaria_dispensa_nome(self):
        class ExtratorTabular(BaseExtractor, ABC):
            pass

        class Concreto(ExtratorTabular, EscalaFakeExtractor):
            nome = "concreto"

        assert Concreto().versao == "1.0.0"


class TestContextoExtracao:
    def test_registrar_usa_aviso_por_padrao(self, tmp_path):
        ctx = ContextoExtracao(caminho=tmp_path)
        ctx.registrar(CodigoInconsistencia.CELULA_ILEGIVEL, "borrada", pagina=2)

        (inc,) = ctx.inconsistencias
        assert inc.severidade is Severidade.AVISO
        assert inc.pagina == 2

    def test_registrar_valida_detalhes(self, tmp_path):
        ctx = ContextoExtracao(caminho=tmp_path)
        with pytest.raises(ValidationError):
            ctx.registrar(CodigoInconsistencia.OUTRO, "x", campo_invalido=1)


# ---------------------------------------------------------------------------
# 3. ExtractorRegistry
# ---------------------------------------------------------------------------


class TestExtractorRegistry:
    def test_registro_mantem_ordem(self):
        registry = ExtractorRegistry([EscalaFakeExtractor(), OutroLayoutExtractor()])

        assert len(registry) == 2
        assert [e.nome for e in registry] == ["escala-fake", "outro-layout"]
        assert "escala-fake" in registry
        assert "inexistente" not in registry

    def test_registrar_retorna_o_proprio_extrator(self):
        extrator = EscalaFakeExtractor()
        assert ExtractorRegistry().registrar(extrator) is extrator

    def test_rejeita_nome_duplicado(self):
        registry = ExtractorRegistry([EscalaFakeExtractor()])
        with pytest.raises(ValueError, match="já registrado"):
            registry.registrar(EscalaFakeExtractor())

    def test_rejeita_objeto_que_nao_e_extrator(self):
        with pytest.raises(TypeError, match="BaseExtractor"):
            ExtractorRegistry().registrar(object())

    def test_obter_e_remover(self):
        extrator = EscalaFakeExtractor()
        registry = ExtractorRegistry([extrator])

        assert registry.obter("escala-fake") is extrator
        registry.remover("escala-fake")
        assert len(registry) == 0
        with pytest.raises(KeyError):
            registry.obter("escala-fake")
        with pytest.raises(KeyError):
            registry.remover("escala-fake")

    def test_lista_de_extratores_e_uma_copia(self):
        registry = ExtractorRegistry([EscalaFakeExtractor()])
        registry.extratores.clear()
        assert len(registry) == 1

    def test_seleciona_extrator_que_reconhece_o_layout(self, criar_pdf):
        fake, outro = EscalaFakeExtractor(), OutroLayoutExtractor()
        registry = ExtractorRegistry([outro, fake])

        assert registry.selecionar(criar_pdf(["ESCALA FAKE"], nome="a.pdf")) is fake
        assert registry.selecionar(criar_pdf(["OUTRO LAYOUT"], nome="b.pdf")) is outro

    def test_primeiro_registrado_tem_prioridade(self, criar_pdf):
        especifico, generico = EscalaFakeExtractor(), GenericoExtractor()
        caminho = criar_pdf(["ESCALA FAKE"])

        assert ExtractorRegistry([especifico, generico]).selecionar(caminho) is especifico
        assert ExtractorRegistry([generico, especifico]).selecionar(caminho) is generico

    def test_fallback_generico(self, criar_pdf):
        generico = GenericoExtractor()
        registry = ExtractorRegistry([EscalaFakeExtractor(), generico])
        assert registry.selecionar(criar_pdf(["LAYOUT DESCONHECIDO"])) is generico

    def test_nenhum_extrator_reconhece(self, criar_pdf):
        registry = ExtractorRegistry([EscalaFakeExtractor(), OutroLayoutExtractor()])
        with pytest.raises(ExtratorNaoEncontrado, match="escala-fake, outro-layout"):
            registry.selecionar(criar_pdf(["LAYOUT DESCONHECIDO"]))

    def test_registry_vazio(self, criar_pdf):
        with pytest.raises(ExtratorNaoEncontrado, match="nenhum"):
            ExtractorRegistry().selecionar(criar_pdf(["ESCALA FAKE"]))

    def test_extrator_nao_encontrado_e_erro_de_extracao(self):
        assert issubclass(ExtratorNaoEncontrado, ErroExtracao)

    def test_extrator_com_falha_no_detector_e_ignorado(self, criar_pdf, caplog):
        fake = EscalaFakeExtractor()
        registry = ExtractorRegistry([QuebradoExtractor(), fake])

        with caplog.at_level(logging.ERROR, logger="extractors.registry"):
            assert registry.selecionar(criar_pdf(["ESCALA FAKE"])) is fake
        assert "quebrado" in caplog.text

    def test_selecionar_arquivo_inexistente(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            ExtractorRegistry([GenericoExtractor()]).selecionar(tmp_path / "x.pdf")

    def test_extrair_delega_ao_extrator_selecionado(self, criar_pdf):
        registry = ExtractorRegistry([OutroLayoutExtractor(), EscalaFakeExtractor()])

        lote = registry.extrair(criar_pdf(LINHAS_VALIDAS))

        assert lote.origem.extrator == "escala-fake"
        assert lote.total_colaboradores == 2

    def test_extrair_sem_extrator_compativel(self, criar_pdf):
        with pytest.raises(ExtratorNaoEncontrado):
            ExtractorRegistry([OutroLayoutExtractor()]).extrair(criar_pdf(LINHAS_VALIDAS))
