package br.com.oficina.execution.execucao.domain.model;

/** Funcionario que executou a acao, como vem das claims do JWT. */
public record Mecanico(String id, String nome) {
}
