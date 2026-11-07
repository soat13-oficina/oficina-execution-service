package br.com.oficina.execution.support.mensageria;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.common.application.mensageria.FabricaEnvelopes;
import br.com.oficina.execution.common.application.mensageria.PublicadorMensagens;
import tools.jackson.databind.json.JsonMapper;

/**
 * Duplo do outbox para os testes de unidade dos participantes da Saga: guarda o que foi publicado,
 * na ordem. Sem transacao nem entrega - quem quer o caminho completo usa o LocalStack.
 */
public class PublicadorMensagensEmMemoria implements PublicadorMensagens {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public record Publicada(String fila, Envelope envelope) {
    }

    private final List<Publicada> publicadas = new ArrayList<>();

    /** Fabrica com relogio fixo, servico "teste". */
    public static FabricaEnvelopes fabrica() {
        return new FabricaEnvelopes(JSON, "oficina-execution-service", Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
    }

    @Override
    public void publicar(String fila, Envelope envelope) {
        publicadas.add(new Publicada(fila, envelope));
    }

    public void limpar() {
        publicadas.clear();
    }

    public List<Publicada> publicadas() {
        return List.copyOf(publicadas);
    }

    public Envelope unica() {
        if (publicadas.size() != 1) {
            throw new AssertionError("Esperada uma mensagem publicada; foram " + publicadas.size() + ": " + tipos());
        }
        return publicadas.get(0).envelope();
    }

    public List<String> tipos() {
        return publicadas.stream().map(p -> p.envelope().type()).toList();
    }

    public <T> T payload(Envelope envelope, Class<T> tipo) {
        return JSON.treeToValue(envelope.payload(), tipo);
    }
}
