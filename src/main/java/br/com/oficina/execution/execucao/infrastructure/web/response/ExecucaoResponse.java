package br.com.oficina.execution.execucao.infrastructure.web.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ExecucaoResponse", description = "A execução de uma OS: etapa atual, diagnóstico e histórico")
public record ExecucaoResponse(
        UUID ordemServicoId,
        String numeroOrdemServico,
        Etapa etapa,
        VeiculoResponse veiculo,
        @Schema(description = "Ponto de partida do mecânico: previsão da abertura ou diagnóstico reaberto")
        List<ServicoResponse> servicosPrevistos,
        List<PecaResponse> pecasPrevistas,
        @Schema(description = "Só existe depois que o diagnóstico é concluído")
        DiagnosticoResponse diagnostico,
        MecanicoResponse mecanicoDiagnostico,
        MecanicoResponse mecanicoExecucao,
        List<TransicaoResponse> historico,
        @Schema(description = "Quando entrou na etapa atual (ordem da fila)")
        Instant entrouNaEtapaEm,
        Instant criadaEm,
        Instant atualizadaEm) {

    public record VeiculoResponse(String placa, String marca, String modelo, Integer ano) {
    }

    public record ServicoResponse(String descricao, BigDecimal valorMaoDeObra) {
    }

    public record PecaResponse(String pecaInsumoId, int quantidade) {
    }

    public record DiagnosticoResponse(String descricao, List<ServicoResponse> servicos, List<PecaResponse> pecas) {
    }

    public record MecanicoResponse(String id, String nome) {
    }

    public record TransicaoResponse(Etapa etapa, Instant em) {
    }

    public static ExecucaoResponse from(Execucao e) {
        return new ExecucaoResponse(
                e.ordemServicoId(),
                e.numeroOrdemServico(),
                e.etapa(),
                new VeiculoResponse(e.veiculo().placa(), e.veiculo().marca(), e.veiculo().modelo(), e.veiculo().ano()),
                e.servicosPrevistos().stream().map(s -> new ServicoResponse(s.descricao(), s.valorMaoDeObra())).toList(),
                e.pecasPrevistas().stream().map(p -> new PecaResponse(p.pecaInsumoId(), p.quantidade())).toList(),
                e.diagnostico() == null ? null : new DiagnosticoResponse(
                        e.diagnostico().descricao(),
                        e.diagnostico().servicos().stream().map(s -> new ServicoResponse(s.descricao(), s.valorMaoDeObra())).toList(),
                        e.diagnostico().pecas().stream().map(p -> new PecaResponse(p.pecaInsumoId(), p.quantidade())).toList()),
                e.mecanicoDiagnostico() == null ? null : new MecanicoResponse(e.mecanicoDiagnostico().id(), e.mecanicoDiagnostico().nome()),
                e.mecanicoExecucao() == null ? null : new MecanicoResponse(e.mecanicoExecucao().id(), e.mecanicoExecucao().nome()),
                e.historico().stream().map(t -> new TransicaoResponse(t.etapa(), t.em())).toList(),
                e.entrouNaEtapaEm(),
                e.criadaEm(),
                e.atualizadaEm());
    }
}
