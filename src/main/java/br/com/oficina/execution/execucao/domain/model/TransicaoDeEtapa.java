package br.com.oficina.execution.execucao.domain.model;

import java.time.Instant;

/** Uma entrada do historico: a execucao chegou a {@code etapa} em {@code em}. */
public record TransicaoDeEtapa(Etapa etapa, Instant em) {
}
