package br.com.oficina.execution.execucao.infrastructure.web.request;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "ConcluirDiagnosticoRequest", description = "Resultado do diagnóstico: o que precisa ser feito e as peças necessárias")
public record ConcluirDiagnosticoRequest(
        @NotBlank(message = "O campo descricaoDiagnostico é obrigatório.")
        @Size(max = 2000, message = "A descrição do diagnóstico deve ter no máximo 2000 caracteres.")
        @Schema(description = "Descrição do que foi encontrado", example = "Óleo vencido e pastilhas dianteiras no limite")
        String descricaoDiagnostico,

        @NotEmpty(message = "Informe ao menos um serviço.")
        @Valid
        List<ServicoRequest> servicos,

        @NotNull(message = "O campo pecas é obrigatório (pode ser vazio).")
        @Valid
        List<PecaRequest> pecas) {

    @Schema(name = "ServicoDiagnosticoRequest")
    public record ServicoRequest(
            @NotBlank(message = "A descrição do serviço é obrigatória.")
            @Size(max = 500, message = "A descrição do serviço deve ter no máximo 500 caracteres.")
            @Schema(example = "Troca de óleo e filtro")
            String descricao,

            @NotNull(message = "O valor da mão de obra é obrigatório.")
            @DecimalMin(value = "0.00", message = "O valor da mão de obra não pode ser negativo.")
            @DecimalMax(value = "9999999999.99", message = "O valor da mão de obra excede o limite.")
            @Schema(description = "Mão de obra do serviço", example = "120.00")
            BigDecimal valorMaoDeObra) {
    }

    @Schema(name = "PecaDiagnosticoRequest")
    public record PecaRequest(
            @NotBlank(message = "O id da peça é obrigatório.")
            @Schema(example = "5e8f1a2b-3c4d-4e5f-8a6b-7c8d9e0f1a2b")
            String pecaInsumoId,

            @Min(value = 1, message = "A quantidade da peça deve ser no mínimo 1.")
            @Schema(example = "1")
            int quantidade) {
    }
}
