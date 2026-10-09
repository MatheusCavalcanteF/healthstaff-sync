package br.com.healthstaff.backend.seguranca;

import jakarta.validation.constraints.NotNull;

/**
 * Corpo de {@code PATCH /api/v1/usuarios/{id}/status}. Valor explícito, não
 * "inverter": repetir a chamada não desfaz a anterior.
 */
public record StatusUsuarioRequest(@NotNull Boolean ativo) {
}
