package br.com.oficina.execution.execucao.domain.model;

/** Copia do veiculo no momento da abertura da OS (o dono do cadastro e o OS Service). */
public record Veiculo(String placa, String marca, String modelo, Integer ano) {
}
