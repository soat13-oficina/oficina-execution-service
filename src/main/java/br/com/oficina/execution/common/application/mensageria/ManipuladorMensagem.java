package br.com.oficina.execution.common.application.mensageria;

import java.util.Set;

/**
 * Trata os tipos de mensagem que declara. Roda dentro da transacao do consumidor, a mesma que
 * registra o messageId como processado: se lancar, nada do efeito fica gravado e a mensagem e
 * entregue de novo.
 *
 * <p>Alem da idempotencia por messageId (garantida pelo consumidor), cada manipulador confere o
 * ESTADO de negocio antes de agir: um comando reenviado por timeout chega com messageId novo.
 */
public interface ManipuladorMensagem {

    Set<String> tipos();

    void tratar(Envelope envelope);
}
