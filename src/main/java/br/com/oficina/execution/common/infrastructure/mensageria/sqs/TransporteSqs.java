package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.infrastructure.mensageria.TransporteMensagens;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envio pelo SQS FIFO (ADR 0005): {@code MessageGroupId} = ordemServicoId (ordem por OS),
 * {@code MessageDeduplicationId} = messageId (reenvio do outbox em ate 5 min descartado pelo SQS) e
 * {@code type}, {@code version} e {@code correlationId} como atributos, para o consumidor rotear sem
 * abrir o corpo. Excecao = nao entregue: o outbox conta a tentativa e tenta de novo.
 */
@Component
@ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "true")
public class TransporteSqs implements TransporteMensagens {

    private final SqsClient sqs;
    private final FilasSqs filas;
    private final JsonMapper jsonMapper;

    public TransporteSqs(SqsClient sqs, FilasSqs filas, JsonMapper jsonMapper) {
        this.sqs = sqs;
        this.filas = filas;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void enviar(MensagemSaida mensagem) {
        JsonNode envelope = jsonMapper.readTree(mensagem.corpo());
        Map<String, MessageAttributeValue> atributos = new HashMap<>();
        atributos.put("type", texto(mensagem.tipo()));
        atributos.put("version", MessageAttributeValue.builder()
                .dataType("Number").stringValue(String.valueOf(envelope.path("version").asInt(1))).build());
        String correlationId = envelope.path("correlationId").asString(null);
        if (correlationId != null && !correlationId.isBlank()) {
            atributos.put("correlationId", texto(correlationId));
        }
        sqs.sendMessage(r -> r.queueUrl(filas.url(mensagem.fila()))
                .messageGroupId(mensagem.groupId())
                .messageDeduplicationId(mensagem.messageId())
                .messageAttributes(atributos)
                .messageBody(mensagem.corpo()));
    }

    private static MessageAttributeValue texto(String valor) {
        return MessageAttributeValue.builder().dataType("String").stringValue(valor).build();
    }
}
