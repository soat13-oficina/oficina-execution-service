package br.com.oficina.execution.execucao.application;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.application.mensageria.PublicadorMensagens;
import br.com.oficina.execution.execucao.domain.model.Execucao;

/**
 * Eventos da execucao para o orquestrador ({@code os-saga-replies}), gravados no outbox na transacao
 * que muda o estado: so saem se ela confirmar.
 */
@Component
public class EventosExecucao {

    private final PublicadorMensagens publicador;
    private final FabricaEnvelopes fabrica;

    public EventosExecucao(PublicadorMensagens publicador, FabricaEnvelopes fabrica) {
        this.publicador = publicador;
        this.fabrica = fabrica;
    }

    /** Resposta a um comando: herda saga e OS e aponta para ele ({@code causationId}). */
    public void responder(Envelope comando, String tipo, Object payload) {
        publicador.publicar(Filas.OS_SAGA_REPLIES, fabrica.resposta(comando, tipo, payload));
    }

    /** Evento de uma acao de pessoa (mecanico): sem {@code causationId}, na saga que abriu a execucao. */
    public void espontaneo(Execucao execucao, String tipo, Object payload) {
        publicador.publicar(Filas.OS_SAGA_REPLIES, fabrica.novo(tipo, execucao.sagaId(), execucao.ordemServicoId(), payload));
    }
}
