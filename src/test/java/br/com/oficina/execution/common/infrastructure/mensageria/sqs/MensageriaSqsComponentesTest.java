package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.infrastructure.mensageria.ConsumidorMensagens;
import br.com.oficina.execution.common.infrastructure.mensageria.TransporteMensagens.MensagemSaida;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/** Partes do transporte SQS que nao precisam do LocalStack. */
class MensageriaSqsComponentesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static PropriedadesSqs propriedades(Set<String> consumo, Duration pausa) {
        return new PropriedadesSqs(true, consumo, "hml", null, null, Duration.ofSeconds(1), 10, pausa, true);
    }

    @Test
    void propriedadesTemPadroesEMontamOsNomesDaConvencao() {
        PropriedadesSqs padrao = new PropriedadesSqs(false, null, " ", null, "", null, 0, null, null);

        assertThat(padrao.consumo()).isEmpty();
        assertThat(padrao.ambiente()).isEqualTo("local");
        assertThat(padrao.regiao()).isEqualTo("us-east-1");
        assertThat(padrao.espera()).isEqualTo(Duration.ofSeconds(20));
        assertThat(padrao.maxMensagens()).isEqualTo(10);
        assertThat(padrao.pausaAposErro()).isEqualTo(Duration.ofSeconds(5));
        assertThat(padrao.receptorAutomatico()).isTrue();
        assertThat(new PropriedadesSqs(true, Set.of(), "prd", null, null, null, 50, null, false).maxMensagens())
                .as("limite do SQS").isEqualTo(10);

        PropriedadesSqs hml = propriedades(Set.of(), Duration.ZERO);
        assertThat(hml.nomeFila("execution-commands")).isEqualTo("oficina-hml-execution-commands.fifo");
        assertThat(hml.nomeDlq("execution-commands")).isEqualTo("oficina-hml-execution-commands-dlq.fifo");
    }

    @Test
    void clienteSqsUsaOEndpointDoLocalStackQuandoInformado() {
        MensageriaSqsConfig config = new MensageriaSqsConfig();

        try (SqsClient localstack = config.sqsClient(new PropriedadesSqs(true, null, "local",
                URI.create("http://localhost:4566"), "sa-east-1", null, 0, null, null));
             SqsClient aws = config.sqsClient(propriedades(Set.of(), Duration.ZERO))) {
            assertThat(localstack.serviceClientConfiguration().endpointOverride()).hasValue(URI.create("http://localhost:4566"));
            assertThat(localstack.serviceClientConfiguration().region().id()).isEqualTo("sa-east-1");
            assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void urlDaFilaEhConsultadaUmaVezPeloNome() {
        SqsClientFalso sqs = new SqsClientFalso();
        FilasSqs filas = new FilasSqs(sqs, propriedades(Set.of(), Duration.ZERO));

        assertThat(filas.url("execution-commands")).isEqualTo("https://sqs.local/oficina-hml-execution-commands.fifo");
        filas.url("execution-commands");
        assertThat(filas.urlDlq("os-saga-replies")).isEqualTo("https://sqs.local/oficina-hml-os-saga-replies-dlq.fifo");

        assertThat(sqs.consultasDeUrl).hasSize(2);
    }

    @Test
    void envelopeSemCorrelacaoNaoLevaOAtributo() {
        SqsClientFalso sqs = new SqsClientFalso();
        TransporteSqs transporte = new TransporteSqs(sqs, new FilasSqs(sqs, propriedades(Set.of(), Duration.ZERO)), JSON);

        transporte.enviar(new MensagemSaida("execution-commands", "os-1", "m-1", "ReservarPecas",
                "{\"type\":\"GerarOrcamento\",\"version\":2}"));

        SendMessageRequest enviada = sqs.enviadas.getFirst();
        assertThat(enviada.messageAttributes()).containsOnlyKeys("type", "version");
        assertThat(enviada.messageAttributes().get("version").stringValue()).isEqualTo("2");
        assertThat(enviada.messageGroupId()).isEqualTo("os-1");
        assertThat(enviada.messageDeduplicationId()).isEqualTo("m-1");
    }

    @Test
    void receptorSobreviveAFalhaDeRedeEParaComAAplicacao() throws InterruptedException {
        SqsClientFalso sqs = new SqsClientFalso();
        sqs.respostas.add(() -> {
            throw SdkClientException.create("sem rede");
        });
        sqs.respostas.add(() -> ReceiveMessageResponse.builder().build());
        PropriedadesSqs props = propriedades(Set.of("execution-commands"), Duration.ofMillis(10));
        ReceptorSqs receptor = new ReceptorSqs(sqs, new FilasSqs(sqs, props), (ConsumidorMensagens) null, props, new SimpleMeterRegistry());

        assertThat(receptor.isAutoStartup()).isTrue();
        receptor.start();
        assertThat(receptor.isRunning()).isTrue();
        for (int i = 0; i < 200 && sqs.receives < 3; i++) {
            Thread.sleep(10);
        }
        receptor.stop();

        assertThat(sqs.receives).as("tentou de novo depois do erro").isGreaterThanOrEqualTo(3);
        assertThat(receptor.isRunning()).isFalse();
    }
}
