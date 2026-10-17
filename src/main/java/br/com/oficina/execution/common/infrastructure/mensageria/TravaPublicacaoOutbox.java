package br.com.oficina.execution.common.infrastructure.mensageria;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Garante que so uma replica publica o outbox por vez (ADR 0005): com duas replicas lendo as mesmas
 * pendentes, a ordem por OS poderia se inverter entre elas.
 *
 * <p>Concessao com prazo (lease) num item da tabela de controle: quem a tem a renova a cada ciclo; se
 * o pod morrer, o prazo vence e outra replica assume. Substitui o advisory lock do PostgreSQL usado
 * no OS e no Billing.
 */
@Component
public class TravaPublicacaoOutbox {

    static final String CHAVE = "LOCK#outbox";
    static final Duration PRAZO = Duration.ofSeconds(15);

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Clock relogio;
    private final String dono = UUID.randomUUID().toString();

    @Autowired
    public TravaPublicacaoOutbox(DynamoDbClient dynamo, PropriedadesDynamo propriedades) {
        this(dynamo, propriedades, Clock.systemUTC());
    }

    TravaPublicacaoOutbox(DynamoDbClient dynamo, PropriedadesDynamo propriedades, Clock relogio) {
        this.dynamo = dynamo;
        this.tabela = propriedades.tabela(Tabelas.CONTROLE);
        this.relogio = relogio;
    }

    /** @return {@code true} se esta replica detem (ou acabou de assumir) a concessao */
    public boolean tentarAdquirir() {
        long agora = relogio.millis();
        try {
            dynamo.putItem(r -> r.tableName(tabela)
                    .item(Map.of(
                            "pk", Atributos.texto(CHAVE),
                            "titular", Atributos.texto(dono),
                            "venceEm", Atributos.numero(agora + PRAZO.toMillis())))
                    .conditionExpression("attribute_not_exists(pk) OR venceEm < :agora OR titular = :titular")
                    .expressionAttributeValues(Map.of(":agora", Atributos.numero(agora), ":titular", Atributos.texto(dono))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
}
