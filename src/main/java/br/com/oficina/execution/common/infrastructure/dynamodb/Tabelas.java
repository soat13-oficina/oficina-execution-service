package br.com.oficina.execution.common.infrastructure.dynamodb;

/** Nomes logicos das tabelas (ADR 0006); o fisico e {@code oficina-<ambiente>-<nome>}. */
public final class Tabelas {

    public static final String PECAS = "pecas";
    public static final String RESERVAS = "reservas";
    public static final String EXECUCOES = "execucoes";
    /** Idempotencia ({@code MSG#}) e outbox ({@code OUTBOX#}). */
    public static final String CONTROLE = "execution-controle";
    /** GSI esparso do controle: so as mensagens do outbox ainda nao publicadas. */
    public static final String INDICE_OUTBOX_PENDENTE = "outbox-pendente";

    private Tabelas() {
    }
}
