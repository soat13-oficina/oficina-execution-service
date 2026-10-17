package br.com.oficina.execution.estoque.application;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.application.mensageria.PublicadorMensagens;

/**
 * Respostas do estoque aos comandos da Saga ({@code os-saga-replies}), com {@code causationId} = o
 * comando. Gravadas no outbox na transacao do consumidor: so saem se a reserva confirmar.
 */
@Component
public class EventosEstoque {

    private final PublicadorMensagens publicador;
    private final FabricaEnvelopes fabrica;

    public EventosEstoque(PublicadorMensagens publicador, FabricaEnvelopes fabrica) {
        this.publicador = publicador;
        this.fabrica = fabrica;
    }

    public void responder(Envelope comando, String tipo, Object payload) {
        publicador.publicar(Filas.OS_SAGA_REPLIES, fabrica.resposta(comando, tipo, payload));
    }
}
