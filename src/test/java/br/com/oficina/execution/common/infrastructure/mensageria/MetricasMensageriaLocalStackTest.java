package br.com.oficina.execution.common.infrastructure.mensageria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.infrastructure.mensageria.sqs.FilasSqs;
import br.com.oficina.execution.common.infrastructure.mensageria.sqs.MetricaDlqSqs;
import br.com.oficina.execution.support.localstack.LocalStackBase;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Os gauges da mensageria contra o DynamoDB e o SQS de verdade (LocalStack): GSI esparso do outbox e
 * GetQueueAttributes da DLQ. Sem Docker, o teste e ignorado.
 */
class MetricasMensageriaLocalStackTest extends LocalStackBase {

    @Autowired
    private OutboxDynamo outbox;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MetricaDlqSqs metricaDlq;

    @Autowired
    private FilasSqs filas;

    private double gauge(String nome, String... tags) {
        return registry.get(nome).tags(tags).gauge().value();
    }

    private void gravarNoOutbox(String messageId) {
        dynamo.transactWriteItems(r -> r.transactItems(
                outbox.item(Filas.OS_SAGA_REPLIES, messageId, "os-1", "PecasReservadas", "{\"a\":1}")));
    }

    @Test
    void outboxPendenteApareceComIdadeESaiQuandoPublicada() {
        assertThat(gauge("oficina.outbox.pendentes")).isZero();
        assertThat(gauge("oficina.outbox.idade.maxima")).isZero();

        gravarNoOutbox("m-1");
        gravarNoOutbox("m-2");

        assertThat(gauge("oficina.outbox.pendentes")).isEqualTo(2);
        assertThat(gauge("oficina.outbox.idade.maxima")).isBetween(0.0, 60.0);

        outbox.marcarPublicada("OUTBOX#m-1");
        outbox.marcarPublicada("OUTBOX#m-2");

        assertThat(gauge("oficina.outbox.pendentes")).isZero();
        assertThat(gauge("oficina.outbox.idade.maxima")).isZero();
    }

    @Test
    void mensagemEsgotadaSaiDoIndiceEEntraNoContadorDeEsgotadas() {
        double antes = gauge("oficina.outbox.esgotadas");
        gravarNoOutbox("m-9");

        outbox.registrarFalha("OUTBOX#m-9", "transporte fora do ar", true);

        assertThat(gauge("oficina.outbox.esgotadas")).isEqualTo(antes + 1);
        assertThat(gauge("oficina.outbox.pendentes")).as("esgotada nao e mais pendente").isZero();
    }

    @Test
    void dlqComMensagensApareceNoGauge() {
        String urlDlq = filas.urlDlq("execution-commands");
        sqsDoTeste.purgeQueue(r -> r.queueUrl(urlDlq));
        metricaDlq.atualizar();
        assertThat(gauge("oficina.mensageria.dlq.mensagens", "fila", "execution-commands")).isZero();

        sqsDoTeste.sendMessage(r -> r.queueUrl(urlDlq).messageGroupId("os-1").messageDeduplicationId("dlq-1")
                .messageBody("{}").messageAttributes(Map.of()));
        metricaDlq.atualizar();

        assertThat(gauge("oficina.mensageria.dlq.mensagens", "fila", "execution-commands")).isEqualTo(1);
        sqsDoTeste.purgeQueue(r -> r.queueUrl(urlDlq));
    }
}
