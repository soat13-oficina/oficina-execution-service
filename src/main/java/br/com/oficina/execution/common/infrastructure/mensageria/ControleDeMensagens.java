package br.com.oficina.execution.common.infrastructure.mensageria;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.infrastructure.dynamodb.Atributos;
import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Idempotencia dos consumidores (ADR 0005): um item {@code MSG#<messageId>} na tabela de controle,
 * gravado na MESMA transacao do efeito da mensagem, com {@code attribute_not_exists(pk)}. Mensagem
 * repetida encontra o item e e descartada; o TTL de 7 dias cobre bem a janela de reentrega do SQS.
 */
@Component
public class ControleDeMensagens {

    /** Rotulo do item na transacao, para o consumidor reconhecer "mensagem repetida". */
    public static final String ROTULO = "idempotencia";
    static final String PREFIXO = "MSG#";
    static final Duration RETENCAO = Duration.ofDays(7);

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Clock relogio;

    @Autowired
    public ControleDeMensagens(DynamoDbClient dynamo, PropriedadesDynamo propriedades) {
        this(dynamo, propriedades, Clock.systemUTC());
    }

    ControleDeMensagens(DynamoDbClient dynamo, PropriedadesDynamo propriedades, Clock relogio) {
        this.dynamo = dynamo;
        this.tabela = propriedades.tabela(Tabelas.CONTROLE);
        this.relogio = relogio;
    }

    /** Leitura com consistencia forte: a mensagem pode ter sido processada um instante atras. */
    public boolean jaProcessada(UUID messageId) {
        return dynamo.getItem(r -> r.tableName(tabela)
                .key(Map.of("pk", Atributos.texto(PREFIXO + messageId)))
                .consistentRead(true)).hasItem();
    }

    /** Item a acrescentar a transacao do efeito. */
    public TransactWriteItem registrar(Envelope envelope) {
        Map<String, AttributeValue> item = Map.of(
                "pk", Atributos.texto(PREFIXO + envelope.messageId()),
                "tipo", Atributos.texto(envelope.type()),
                "ordemServicoId", Atributos.texto(envelope.ordemServicoId().toString()),
                "processadaEm", Atributos.texto(relogio.instant().toString()),
                "expiraEm", Atributos.numero(relogio.instant().plus(RETENCAO).getEpochSecond()));
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build()).build();
    }
}
