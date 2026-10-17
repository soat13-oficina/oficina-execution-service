package br.com.oficina.execution.estoque.infrastructure.web.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(name = "AdicionarEstoquePecaRequest", description = "Dados para adicionar estoque")
public record AdicionarEstoquePecaRequest(
        @Min(value = 1, message = "A quantidade deve ser no mínimo 1.")
        @Schema(description = "Quantidade a adicionar ao estoque", example = "5")
        int quantidade) {
}
