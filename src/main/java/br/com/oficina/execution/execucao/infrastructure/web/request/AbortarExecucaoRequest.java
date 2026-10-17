package br.com.oficina.execution.execucao.infrastructure.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "AbortarExecucaoRequest", description = "Por que o serviço não pôde ser concluído")
public record AbortarExecucaoRequest(
        @NotBlank(message = "O campo motivo é obrigatório.")
        @Size(max = 500, message = "O motivo deve ter no máximo 500 caracteres.")
        @Schema(description = "Motivo do aborto", example = "Peça com defeito de fábrica; cliente precisa ser consultado")
        String motivo) {
}
