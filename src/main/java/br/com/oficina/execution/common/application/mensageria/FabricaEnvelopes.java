package br.com.oficina.execution.common.application.mensageria;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Monta envelopes com o que vem do contexto: {@code source} e o nome logico do servico
 * ({@code mensageria.servico}, ver convencoes de nomes) e o
 * {@code correlationId} e o do MDC - preenchido pelo CorrelationIdFilter numa requisicao HTTP e
 * pelo consumidor de mensagens ao processar uma mensagem, para que a correlacao atravesse a Saga.
 */
@Component
public class FabricaEnvelopes {

    /** Mesma chave do CorrelationIdFilter. */
    public static final String MDC_CORRELATION_ID = "correlation_id";
    public static final int VERSAO_INICIAL = 1;

    private final JsonMapper jsonMapper;
    private final String servico;
    private final Clock relogio;

    @Autowired
    public FabricaEnvelopes(JsonMapper jsonMapper, @Value("${mensageria.servico}") String servico) {
        this(jsonMapper, servico, Clock.systemUTC());
    }

    /** Com relogio explicito, para testes. */
    public FabricaEnvelopes(JsonMapper jsonMapper, String servico, Clock relogio) {
        this.jsonMapper = jsonMapper;
        this.servico = servico;
        this.relogio = relogio;
    }

    /** Comando do orquestrador, ou evento espontaneo: sem {@code causationId}. */
    public Envelope novo(String tipo, UUID sagaId, UUID ordemServicoId, Object payload) {
        return montar(tipo, sagaId, ordemServicoId, null, payload);
    }

    /** Evento de resposta a um comando: herda saga, OS e correlacao, e aponta para a causa. */
    public Envelope resposta(Envelope comando, String tipo, Object payload) {
        return montar(tipo, comando.sagaId(), comando.ordemServicoId(), comando.messageId(), payload);
    }

    private Envelope montar(String tipo, UUID sagaId, UUID ordemServicoId, UUID causationId, Object payload) {
        String correlationId = MDC.get(MDC_CORRELATION_ID);
        return new Envelope(
                UUID.randomUUID(),
                tipo,
                VERSAO_INICIAL,
                sagaId,
                ordemServicoId,
                correlationId != null ? correlationId : UUID.randomUUID().toString(),
                causationId,
                Instant.now(relogio),
                servico,
                jsonMapper.valueToTree(payload));
    }
}
