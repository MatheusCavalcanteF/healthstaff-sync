package br.com.healthstaff.backend.extrator;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Conexão com o microsserviço de extração (python-extractor).
 *
 * @param url             endereço base do extrator (ex.: {@code http://localhost:8000})
 * @param apiKey          enviada no cabeçalho {@code X-API-Key}; vazia = sem autenticação
 * @param timeoutConexao  tempo máximo para abrir a conexão
 * @param timeoutLeitura  tempo máximo de espera pela extração (arquivos grandes demoram)
 * @param timeoutSaude    prazo da consulta de saúde ({@code GET /health}), para conectar e
 *                        para responder: curto, para o healthcheck não ficar preso
 */
@Validated
@ConfigurationProperties("healthstaff.extrator")
public record ExtratorProperties(
		@NotNull URI url,
		String apiKey,
		@DefaultValue("5s") Duration timeoutConexao,
		@DefaultValue("120s") Duration timeoutLeitura,
		@DefaultValue("3s") Duration timeoutSaude) {

	public boolean possuiApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}
}
