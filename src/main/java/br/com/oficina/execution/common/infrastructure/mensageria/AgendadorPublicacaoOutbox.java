package br.com.oficina.execution.common.infrastructure.mensageria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara o {@link PublicadorOutbox} a cada {@code mensageria.outbox.intervalo-ms} (1 s por
 * padrao). Desligavel por {@code mensageria.outbox.agendamento-habilitado=false} - os testes
 * desligam e chamam o publicador direto, para nao competir com ele.
 */
@Component
@ConditionalOnProperty(name = "mensageria.outbox.agendamento-habilitado", havingValue = "true", matchIfMissing = true)
public class AgendadorPublicacaoOutbox {
    private static final Logger log = LoggerFactory.getLogger(AgendadorPublicacaoOutbox.class);

    private final PublicadorOutbox publicador;

    public AgendadorPublicacaoOutbox(PublicadorOutbox publicador) {
        this.publicador = publicador;
    }

    @Scheduled(fixedDelayString = "${mensageria.outbox.intervalo-ms:1000}")
    public void publicar() {
        try {
            publicador.publicarPendentes();
        } catch (RuntimeException e) {
            // Falha do ciclo inteiro (banco fora, por exemplo): o proximo ciclo tenta de novo.
            log.error("Falha no ciclo de publicacao do outbox. erro={}", e.toString());
        }
    }
}
