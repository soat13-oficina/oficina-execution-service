package br.com.oficina.execution.estoque.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.Filas;
import br.com.oficina.execution.common.application.mensageria.PublicadorMensagens;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Respostas do estoque aos comandos da Saga ({@code os-saga-replies}), com {@code causationId} = o
 * comando. Gravadas no outbox na transacao do consumidor: so saem se a reserva confirmar.
 */
@Component
public class EventosEstoque {

    private final PublicadorMensagens publicador;
    private final FabricaEnvelopes fabrica;
    private final MeterRegistry registry;

    @Autowired
    public EventosEstoque(PublicadorMensagens publicador, FabricaEnvelopes fabrica, MeterRegistry registry) {
        this.publicador = publicador;
        this.fabrica = fabrica;
        this.registry = registry;
    }

    /** Sem metricas compartilhadas: para quem monta o publicador na mao (testes). */
    public EventosEstoque(PublicadorMensagens publicador, FabricaEnvelopes fabrica) {
        this(publicador, fabrica, new SimpleMeterRegistry());
    }

    public void responder(Envelope comando, String tipo, Object payload) {
        // Metrica de negocio do estoque (PLANO-FASE-4.md, secao 12): reserva atendida x recusada por falta de peca.
        if (MensagensEstoque.PECAS_RESERVADAS.equals(tipo)) {
            registry.counter("oficina.estoque.reservas", "resultado", "ok").increment();
        } else if (MensagensEstoque.RESERVA_FALHOU.equals(tipo)) {
            registry.counter("oficina.estoque.reservas", "resultado", "falha").increment();
        }
        publicador.publicar(Filas.OS_SAGA_REPLIES, fabrica.resposta(comando, tipo, payload));
    }
}
