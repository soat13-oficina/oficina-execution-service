package br.com.oficina.execution.execucao.domain.model;

/** Etapas da execucao de uma OS (ADR 0006). As duas "AGUARDANDO" sao as filas do mecanico. */
public enum Etapa {
    AGUARDANDO_DIAGNOSTICO,
    EM_DIAGNOSTICO,
    DIAGNOSTICO_CONCLUIDO,
    AGUARDANDO_EXECUCAO,
    EM_EXECUCAO,
    CONCLUIDA,
    ABORTADA,
    CANCELADA;

    public boolean ehFila() {
        return this == AGUARDANDO_DIAGNOSTICO || this == AGUARDANDO_EXECUCAO;
    }

    /** Sem volta: nenhuma operacao muda a execucao depois daqui. */
    public boolean ehFinal() {
        return this == CONCLUIDA || this == ABORTADA || this == CANCELADA;
    }
}
