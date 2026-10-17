package br.com.oficina.execution.common.application.mensageria;

/**
 * Nomes LOGICOS das filas da Saga (ADR 0005). O transporte traduz para o nome fisico do
 * ambiente - no SQS, {@code oficina-<env>-<logico>.fifo}.
 */
public final class Filas {

    /** Comandos do orquestrador para o Billing. */
    public static final String BILLING_COMMANDS = "billing-commands";
    /** Comandos do orquestrador para a Execution. */
    public static final String EXECUTION_COMMANDS = "execution-commands";
    /** Eventos de resposta do Billing e da Execution para o orquestrador. */
    public static final String OS_SAGA_REPLIES = "os-saga-replies";

    private Filas() {
    }
}
