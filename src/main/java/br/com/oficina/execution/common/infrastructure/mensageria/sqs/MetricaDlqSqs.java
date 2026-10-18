package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Profundidade da DLQ de cada fila que este servico le ({@code oficina.mensageria.dlq.mensagens{fila}}).
 *
 * <p>Lida direto do SQS ({@code GetQueueAttributes}), e nao pela integracao AWS do Datadog: o alerta
 * "tem mensagem na DLQ" nao depende de uma integracao de conta que ninguem do grupo precise configurar. Soma as
 * visiveis e as em voo. Se a leitura falhar, o ultimo valor fica (um erro de rede nao zera o alarme).
 */
@Component
@ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "true")
public class MetricaDlqSqs {
    private static final Logger log = LoggerFactory.getLogger(MetricaDlqSqs.class);

    private final SqsClient sqs;
    private final FilasSqs filas;
    private final Map<String, AtomicLong> profundidade = new ConcurrentHashMap<>();

    public MetricaDlqSqs(SqsClient sqs, FilasSqs filas, PropriedadesSqs propriedades, MeterRegistry registry) {
        this.sqs = sqs;
        this.filas = filas;
        for (String fila : propriedades.consumo()) {
            AtomicLong valor = new AtomicLong();
            profundidade.put(fila, valor);
            registry.gauge("oficina.mensageria.dlq.mensagens", List.of(Tag.of("fila", fila)), valor);
        }
    }

    @Scheduled(fixedDelayString = "${mensageria.sqs.dlq-intervalo:30s}", initialDelayString = "${mensageria.sqs.dlq-intervalo:30s}")
    public void atualizar() {
        profundidade.forEach((fila, valor) -> {
            try {
                var atributos = sqs.getQueueAttributes(r -> r.queueUrl(filas.urlDlq(fila))
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
                valor.set(numero(atributos.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                        + numero(atributos.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));
            } catch (SdkException | IllegalStateException e) {
                log.warn("Nao foi possivel ler a profundidade da DLQ; mantendo o ultimo valor. fila={}, erro={}", fila, e.toString());
            }
        });
    }

    private static long numero(String texto) {
        return texto == null ? 0 : Long.parseLong(texto);
    }
}
