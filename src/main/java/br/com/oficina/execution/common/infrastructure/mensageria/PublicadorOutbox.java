package br.com.oficina.execution.common.infrastructure.mensageria;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.infrastructure.mensageria.OutboxDynamo.Pendente;
import br.com.oficina.execution.common.infrastructure.mensageria.TransporteMensagens.MensagemSaida;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Envia as mensagens pendentes do outbox pelo transporte ativo (ADR 0005).
 *
 * <p>Regras:
 * <ul>
 *   <li>ordem de gravacao - a mesma em que a Saga as produziu;</li>
 *   <li>falhou uma mensagem de uma OS, as seguintes DAQUELA OS esperam o proximo ciclo - enviar a
 *       seguinte inverteria a ordem; as outras OSs seguem normalmente;</li>
 *   <li>esgotadas as tentativas, a mensagem sai do indice (equivale a DLQ) e e logada como erro.</li>
 * </ul>
 * Uma replica por vez ({@link TravaPublicacaoOutbox}). Duplicata residual (pod cai depois de enviar
 * e antes de marcar) e absorvida pela deduplicacao do SQS e pela idempotencia do consumidor.
 */
@Component
public class PublicadorOutbox {
    private static final Logger log = LoggerFactory.getLogger(PublicadorOutbox.class);

    private final OutboxDynamo outbox;
    private final TransporteMensagens transporte;
    private final TravaPublicacaoOutbox trava;
    private final MeterRegistry meterRegistry;
    private final int maxTentativas;
    private final int lote;

    public PublicadorOutbox(
            OutboxDynamo outbox,
            TransporteMensagens transporte,
            TravaPublicacaoOutbox trava,
            MeterRegistry meterRegistry,
            @Value("${mensageria.outbox.max-tentativas:5}") int maxTentativas,
            @Value("${mensageria.outbox.lote:100}") int lote) {
        this.outbox = outbox;
        this.transporte = transporte;
        this.trava = trava;
        this.meterRegistry = meterRegistry;
        this.maxTentativas = maxTentativas;
        this.lote = lote;
    }

    /** @return quantas mensagens foram entregues neste ciclo */
    public int publicarPendentes() {
        if (!trava.tentarAdquirir()) {
            return 0;
        }
        List<Pendente> pendentes = outbox.pendentes(lote);
        Set<String> gruposComFalha = new HashSet<>();
        int entregues = 0;
        for (Pendente mensagem : pendentes) {
            if (gruposComFalha.contains(mensagem.grupo())) {
                continue;
            }
            if (enviar(mensagem)) {
                entregues++;
            } else {
                gruposComFalha.add(mensagem.grupo());
            }
        }
        return entregues;
    }

    private boolean enviar(Pendente mensagem) {
        try {
            transporte.enviar(new MensagemSaida(
                    mensagem.fila(), mensagem.grupo(), mensagem.messageId(), mensagem.tipo(), mensagem.corpo()));
        } catch (RuntimeException e) {
            registrarFalha(mensagem, e);
            return false;
        }
        outbox.marcarPublicada(mensagem.pk());
        meterRegistry.counter("oficina.mensageria.publicadas", "fila", mensagem.fila(), "tipo", mensagem.tipo()).increment();
        return true;
    }

    private void registrarFalha(Pendente mensagem, RuntimeException e) {
        int tentativa = mensagem.tentativas() + 1;
        boolean esgotada = tentativa >= maxTentativas;
        outbox.registrarFalha(mensagem.pk(), e.toString(), esgotada);
        meterRegistry.counter("oficina.mensageria.falhas", "fila", mensagem.fila(), "tipo", mensagem.tipo()).increment();
        if (esgotada) {
            log.error("Mensagem do outbox esgotou as tentativas e nao sera mais enviada. messageId={}, tipo={}, fila={}, erro={}",
                    mensagem.messageId(), mensagem.tipo(), mensagem.fila(), e.toString());
        } else {
            log.warn("Falha ao enviar mensagem do outbox; nova tentativa no proximo ciclo. messageId={}, tipo={}, tentativa={}, erro={}",
                    mensagem.messageId(), mensagem.tipo(), tentativa, e.toString());
        }
    }
}
