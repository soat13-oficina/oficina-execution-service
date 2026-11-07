package br.com.oficina.execution.execucao.domain.model;

import java.util.List;

public record Diagnostico(String descricao, List<ServicoDiagnostico> servicos, List<PecaDiagnostico> pecas) {

    public Diagnostico {
        servicos = List.copyOf(servicos);
        pecas = List.copyOf(pecas);
    }
}
