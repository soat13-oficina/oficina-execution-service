package br.com.oficina.execution.common.infrastructure.mensageria;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sem SQS habilitado (desenvolvimento sem LocalStack, testes): nada sai do outbox. Cada envio falha e
 * fica pendente, como se a fila estivesse fora - o outbox tenta de novo no proximo ciclo.
 */
@Component
@ConditionalOnProperty(name = "mensageria.sqs.habilitado", havingValue = "false", matchIfMissing = true)
public class TransporteIndisponivel implements TransporteMensagens {

    @Override
    public void enviar(MensagemSaida mensagem) {
        throw new IllegalStateException("Mensageria SQS desligada (mensageria.sqs.habilitado=false): mensagem continua no outbox");
    }
}
