package br.com.oficina.execution.estoque.infrastructure.mensageria;

import java.util.Set;

import org.springframework.stereotype.Component;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.ManipuladorMensagem;
import br.com.oficina.execution.estoque.application.MensagensEstoque;
import br.com.oficina.execution.estoque.application.ReservasPelaSaga;

/** Comandos do orquestrador ({@code execution-commands}) que o estoque atende. */
@Component
public class ManipuladorComandosEstoque implements ManipuladorMensagem {

    private final ReservasPelaSaga reservas;

    public ManipuladorComandosEstoque(ReservasPelaSaga reservas) {
        this.reservas = reservas;
    }

    @Override
    public Set<String> tipos() {
        return Set.of(MensagensEstoque.RESERVAR_PECAS, MensagensEstoque.LIBERAR_RESERVA);
    }

    @Override
    public void tratar(Envelope envelope) {
        if (MensagensEstoque.RESERVAR_PECAS.equals(envelope.type())) {
            reservas.reservar(envelope);
        } else {
            reservas.liberar(envelope);
        }
    }
}
