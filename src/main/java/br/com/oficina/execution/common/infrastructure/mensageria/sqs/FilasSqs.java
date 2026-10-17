package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * URL de cada fila descoberta por {@code GetQueueUrl} a partir do nome - os produtores nao leem o
 * state Terraform de quem e dono da fila (ADR 0005). Guardada depois da primeira consulta.
 */
@Component
@ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "true")
public class FilasSqs {

    private final SqsClient sqs;
    private final PropriedadesSqs propriedades;
    private final Map<String, String> urls = new ConcurrentHashMap<>();

    public FilasSqs(SqsClient sqs, PropriedadesSqs propriedades) {
        this.sqs = sqs;
        this.propriedades = propriedades;
    }

    public String url(String filaLogica) {
        return resolver(propriedades.nomeFila(filaLogica));
    }

    public String urlDlq(String filaLogica) {
        return resolver(propriedades.nomeDlq(filaLogica));
    }

    private String resolver(String nome) {
        return urls.computeIfAbsent(nome, n -> sqs.getQueueUrl(r -> r.queueName(n)).queueUrl());
    }
}
