package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.infrastructure.mensageria.ConsumidorMensagens;
import br.com.oficina.execution.common.infrastructure.mensageria.MensagemInvalidaException;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Le as filas de {@code mensageria.sqs.consumo} com long polling e entrega cada mensagem ao
 * {@link ConsumidorMensagens} (idempotencia, MDC, metricas). Uma thread propria por fila: o long polling de 20 s nao pode ocupar o agendador do
 * Spring, que dispara o outbox.
 *
 * <p>Regras do ADR 0005:
 * <ul>
 *   <li>sucesso: {@code DeleteMessage};</li>
 *   <li>excecao: a mensagem fica e volta depois do {@code VisibilityTimeout}; as seguintes do mesmo
 *       grupo (OS) no lote nao sao processadas, para nao inverter a ordem. Cinco falhas: o SQS a move
 *       para a DLQ;</li>
 *   <li>fora do contrato ou sem manipulador ({@link MensagemInvalidaException}): vai direto para a
 *       DLQ - tentar de novo nao adianta.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "true")
public class ReceptorSqs implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ReceptorSqs.class);
    private static final int LIMITE_ERRO = 250;

    private final SqsClient sqs;
    private final FilasSqs filas;
    private final ConsumidorMensagens consumidor;
    private final PropriedadesSqs propriedades;
    private final MeterRegistry meterRegistry;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean rodando;

    public ReceptorSqs(
            SqsClient sqs,
            FilasSqs filas,
            ConsumidorMensagens consumidor,
            PropriedadesSqs propriedades,
            MeterRegistry meterRegistry) {
        this.sqs = sqs;
        this.filas = filas;
        this.consumidor = consumidor;
        this.propriedades = propriedades;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Um receive e o processamento do que veio.
     *
     * @return quantas mensagens foram apagadas da fila (processadas ou mandadas para a DLQ)
     */
    public int receberLote(String filaLogica) {
        String url = filas.url(filaLogica);
        List<Message> mensagens = sqs.receiveMessage(r -> r.queueUrl(url)
                .maxNumberOfMessages(propriedades.maxMensagens())
                .waitTimeSeconds((int) propriedades.espera().toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                .messageAttributeNames("All")).messages();
        Set<String> gruposComFalha = new HashSet<>();
        int apagadas = 0;
        for (Message mensagem : mensagens) {
            String grupo = mensagem.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID);
            if (grupo != null && gruposComFalha.contains(grupo)) {
                continue;
            }
            try {
                consumidor.consumir(mensagem.body());
                apagar(url, mensagem);
                apagadas++;
            } catch (MensagemInvalidaException e) {
                log.error("Mensagem fora do contrato enviada para a DLQ. fila={}, sqsMessageId={}, erro={}",
                        filaLogica, mensagem.messageId(), e.getMessage());
                enviarParaDlq(filaLogica, mensagem, grupo, e);
                apagar(url, mensagem);
                apagadas++;
                contar(filaLogica, "dlq");
            } catch (RuntimeException e) {
                // Fica na fila; volta depois do VisibilityTimeout. Ordem do grupo preservada.
                log.warn("Falha ao processar mensagem; nova tentativa apos o visibility timeout. fila={}, grupo={}, sqsMessageId={}, erro={}",
                        filaLogica, grupo, mensagem.messageId(), e.toString());
                if (grupo != null) {
                    gruposComFalha.add(grupo);
                }
                contar(filaLogica, "falha");
            }
        }
        return apagadas;
    }

    private void apagar(String url, Message mensagem) {
        sqs.deleteMessage(r -> r.queueUrl(url).receiptHandle(mensagem.receiptHandle()));
    }

    private void enviarParaDlq(String filaLogica, Message mensagem, String grupo, RuntimeException erro) {
        String motivo = erro.getMessage() == null ? erro.getClass().getSimpleName() : erro.getMessage();
        sqs.sendMessage(r -> r.queueUrl(filas.urlDlq(filaLogica))
                .messageGroupId(grupo == null ? "fora-do-contrato" : grupo)
                .messageDeduplicationId(mensagem.messageId())
                .messageAttributes(java.util.Map.of("erro", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(motivo.length() <= LIMITE_ERRO ? motivo : motivo.substring(0, LIMITE_ERRO))
                        .build()))
                .messageBody(mensagem.body()));
    }

    private void contar(String fila, String resultado) {
        meterRegistry.counter("oficina.mensageria.sqs.recebidas", "fila", fila, "resultado", resultado).increment();
    }

    private void laco(String filaLogica) {
        log.info("Receptor SQS iniciado. fila={}, nome={}", filaLogica, propriedades.nomeFila(filaLogica));
        while (rodando) {
            try {
                receberLote(filaLogica);
            } catch (AbortedException e) {
                // Interrompido no stop().
                break;
            } catch (SdkException | IllegalStateException e) {
                if (!rodando) {
                    break;
                }
                log.warn("Falha ao ler a fila; nova tentativa em {}. fila={}, erro={}",
                        propriedades.pausaAposErro(), filaLogica, e.toString());
                dormir();
            }
        }
        log.info("Receptor SQS parado. fila={}", filaLogica);
    }

    private void dormir() {
        try {
            Thread.sleep(propriedades.pausaAposErro());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rodando = false;
        }
    }

    @Override
    public void start() {
        rodando = true;
        for (String fila : propriedades.consumo()) {
            threads.add(Thread.ofPlatform().name("receptor-sqs-" + fila).daemon(true).start(() -> laco(fila)));
        }
    }

    @Override
    public void stop() {
        rodando = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(propriedades.espera().plusSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }

    @Override
    public boolean isAutoStartup() {
        return propriedades.receptorAutomatico();
    }
}
