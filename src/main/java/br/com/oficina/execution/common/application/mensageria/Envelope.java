package br.com.oficina.execution.common.application.mensageria;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Envelope comum a todo comando e evento da Saga - contrato em
 * {@code docs/contratos/mensagens.md} e {@code docs/contratos/schemas/envelope.schema.json}.
 *
 * @param messageId      id unico; tambem e o MessageDeduplicationId do SQS e a chave de idempotencia
 * @param type           nome do comando ou evento (ex.: {@code GerarOrcamento})
 * @param version        versao do contrato daquele tipo
 * @param ordemServicoId tambem e o MessageGroupId do SQS FIFO: ordem garantida por OS
 * @param correlationId  x-request-id da requisicao de origem, carregado de ponta a ponta
 * @param causationId    messageId do comando que originou este evento; {@code null} em comandos
 *                       e em eventos espontaneos
 * @param source         servico produtor ({@code oficina-os-service}...)
 */
public record Envelope(
        UUID messageId,
        String type,
        int version,
        UUID sagaId,
        UUID ordemServicoId,
        String correlationId,
        UUID causationId,
        Instant occurredAt,
        String source,
        JsonNode payload) {

    public Envelope {
        if (messageId == null || type == null || type.isBlank() || ordemServicoId == null || payload == null) {
            throw new IllegalArgumentException("Envelope incompleto: messageId, type, ordemServicoId e payload sao obrigatorios");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Versao do contrato deve ser >= 1");
        }
    }
}
