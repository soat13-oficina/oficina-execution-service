package br.com.oficina.execution.common.infrastructure.dynamodb;

import java.util.List;

/**
 * A transacao do DynamoDB foi cancelada. {@code rotulos} sao os rotulos dos itens cuja condicao
 * falhou (o DynamoDB informa o motivo por item) - e o que separa "mensagem repetida" (item de
 * idempotencia) de "outra escrita concorrente mexeu na peca".
 */
public class EscritaRecusadaException extends RuntimeException {

    private final transient List<String> rotulos;

    public EscritaRecusadaException(List<String> rotulos, Throwable causa) {
        super("Transacao do DynamoDB cancelada; condicao falhou em: " + rotulos, causa);
        this.rotulos = List.copyOf(rotulos);
    }

    public List<String> rotulos() {
        return rotulos;
    }

    /** Algum item com rotulo que comeca por {@code prefixo} teve a condicao recusada. */
    public boolean recusou(String prefixo) {
        return rotulos.stream().anyMatch(r -> r.startsWith(prefixo));
    }
}
