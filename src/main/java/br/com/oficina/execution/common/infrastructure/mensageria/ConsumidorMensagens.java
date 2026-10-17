package br.com.oficina.execution.common.infrastructure.mensageria;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.ManipuladorMensagem;
import br.com.oficina.execution.common.infrastructure.dynamodb.EscritaRecusadaException;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recebe uma mensagem do transporte e a entrega ao {@link ManipuladorMensagem} do seu tipo, com
 * idempotencia por messageId (ADR 0005).
 *
 * <p>Tudo numa unidade de trabalho: o efeito do manipulador, as respostas no outbox e o registro de
 * mensagem processada confirmam juntos numa unica transacao do DynamoDB, ou nao confirmam. Lancar
 * devolve a mensagem ao transporte, que tenta de novo.
 */
@Component
public class ConsumidorMensagens {
    private static final Logger log = LoggerFactory.getLogger(ConsumidorMensagens.class);

    static final String MDC_SAGA_ID = "saga_id";
    static final String MDC_ORDEM_SERVICO_ID = "ordem_servico_id";
    static final String MDC_MESSAGE_ID = "message_id";
    static final String MDC_MESSAGE_TYPE = "message_type";

    private final Map<String, ManipuladorMensagem> manipuladoresPorTipo = new HashMap<>();
    private final ControleDeMensagens controle;
    private final UnidadeDeTrabalho unidade;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    public ConsumidorMensagens(
            ObjectProvider<ManipuladorMensagem> manipuladores,
            ControleDeMensagens controle,
            UnidadeDeTrabalho unidade,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry) {
        for (ManipuladorMensagem manipulador : manipuladores.orderedStream().toList()) {
            for (String tipo : manipulador.tipos()) {
                ManipuladorMensagem anterior = manipuladoresPorTipo.putIfAbsent(tipo, manipulador);
                if (anterior != null) {
                    throw new IllegalStateException("Dois manipuladores para o tipo " + tipo + ": "
                            + anterior.getClass().getSimpleName() + " e " + manipulador.getClass().getSimpleName());
                }
            }
        }
        this.controle = controle;
        this.unidade = unidade;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    public void consumir(String corpo) {
        Envelope envelope = ler(corpo);
        if (controle.jaProcessada(envelope.messageId())) {
            descartarDuplicada(envelope);
            return;
        }
        ManipuladorMensagem manipulador = manipuladoresPorTipo.get(envelope.type());
        if (manipulador == null) {
            throw new MensagemInvalidaException("Nenhum manipulador para o tipo " + envelope.type());
        }
        Map<String, String> mdcAnterior = MDC.getCopyOfContextMap();
        try {
            preencherMdc(envelope);
            unidade.executar(() -> {
                manipulador.tratar(envelope);
                unidade.adicionar(ControleDeMensagens.ROTULO, controle.registrar(envelope));
            });
            contar(envelope, "processada");
        } catch (EscritaRecusadaException e) {
            // Duas entregas da mesma mensagem em paralelo: a outra confirmou primeiro.
            if (!e.recusou(ControleDeMensagens.ROTULO)) {
                throw e;
            }
            descartarDuplicada(envelope);
        } finally {
            if (mdcAnterior == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdcAnterior);
            }
        }
    }

    private void descartarDuplicada(Envelope envelope) {
        log.info("Mensagem duplicada descartada. messageId={}, tipo={}", envelope.messageId(), envelope.type());
        contar(envelope, "duplicada");
    }

    private Envelope ler(String corpo) {
        try {
            return jsonMapper.readValue(corpo, Envelope.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MensagemInvalidaException("Mensagem fora do contrato: " + e.getMessage(), e);
        }
    }

    /** Mesmas chaves snake_case do resto da aplicacao: o log estruturado as leva ao nivel raiz do JSON. */
    private static void preencherMdc(Envelope envelope) {
        MDC.put(FabricaEnvelopes.MDC_CORRELATION_ID, envelope.correlationId());
        MDC.put(MDC_ORDEM_SERVICO_ID, envelope.ordemServicoId().toString());
        MDC.put(MDC_MESSAGE_ID, envelope.messageId().toString());
        MDC.put(MDC_MESSAGE_TYPE, envelope.type());
        if (envelope.sagaId() != null) {
            MDC.put(MDC_SAGA_ID, envelope.sagaId().toString());
        }
    }

    private void contar(Envelope envelope, String resultado) {
        meterRegistry.counter("oficina.mensageria.consumidas", "tipo", envelope.type(), "resultado", resultado).increment();
    }
}
