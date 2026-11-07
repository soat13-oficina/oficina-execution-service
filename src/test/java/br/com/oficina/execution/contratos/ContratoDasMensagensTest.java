package br.com.oficina.execution.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.estoque.application.EventosEstoque;
import br.com.oficina.execution.estoque.application.ReservasPelaSaga;
import br.com.oficina.execution.estoque.infrastructure.mensageria.ManipuladorComandosEstoque;
import br.com.oficina.execution.support.contratos.ContratosDeMensagens;
import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;
import br.com.oficina.execution.support.persistence.ReservaPecaRepositoryEmMemoria;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Teste de contrato (ADR 0005) dos dois lados, contra os JSON Schemas copiados do OS Service:
 * <ul>
 *   <li>consumidor: o exemplo de cada comando que o estoque recebe, como o contrato documenta, e
 *       processado sem erro;</li>
 *   <li>produtor: todo evento que o estoque publica passa no JSON Schema do seu tipo.</li>
 * </ul>
 */
class ContratoDasMensagensTest {

    private static final ContratosDeMensagens CONTRATOS = new ContratosDeMensagens();

    private PecaInsumoRepositoryEmMemoria pecas;
    private PublicadorMensagensEmMemoria publicador;
    private ManipuladorComandosEstoque manipulador;

    @BeforeEach
    void preparar() {
        pecas = new PecaInsumoRepositoryEmMemoria();
        publicador = new PublicadorMensagensEmMemoria();
        manipulador = new ManipuladorComandosEstoque(new ReservasPelaSaga(
                pecas, new ReservaPecaRepositoryEmMemoria(pecas),
                new EventosEstoque(publicador, PublicadorMensagensEmMemoria.fabrica()), PublicadorMensagensEmMemoria.JSON));
    }

    private Envelope consumir(ObjectNode exemplo) {
        exemplo.put("messageId", UUID.randomUUID().toString());
        Envelope envelope = PublicadorMensagensEmMemoria.JSON.treeToValue(exemplo, Envelope.class);
        manipulador.tratar(envelope);
        return envelope;
    }

    private List<String> errosDoUltimoEvento() {
        String json = PublicadorMensagensEmMemoria.JSON.writeValueAsString(publicador.publicadas().getLast().envelope());
        return CONTRATOS.validar(json);
    }

    @Test
    void oContratoTemSchemaParaOsTiposQueOEstoqueTrata() {
        assertThat(CONTRATOS.tipos()).contains("ReservarPecas", "LiberarReserva", "PecasReservadas", "ReservaFalhou", "ReservaLiberada");
    }

    @Test
    void reservarPecasDoExemploRespondePecasReservadasDentroDoContrato() {
        ObjectNode exemplo = (ObjectNode) CONTRATOS.exemplo("ReservarPecas");
        for (JsonNode item : exemplo.get("payload").get("itens")) {
            pecas.semear(item.get("pecaInsumoId").asString(), "Peca do exemplo", "80.00", 10);
        }

        Envelope comando = consumir(exemplo);

        assertThat(publicador.unica().type()).isEqualTo("PecasReservadas");
        assertThat(publicador.unica().causationId()).isEqualTo(comando.messageId());
        assertThat(errosDoUltimoEvento()).as("PecasReservadas fora do contrato").isEmpty();
    }

    @Test
    void estoqueInsuficienteRespondeReservaFalhouDentroDoContrato() {
        // Nenhuma peca do exemplo existe: todas faltam.
        consumir((ObjectNode) CONTRATOS.exemplo("ReservarPecas"));

        assertThat(publicador.unica().type()).isEqualTo("ReservaFalhou");
        assertThat(errosDoUltimoEvento()).as("ReservaFalhou fora do contrato").isEmpty();
    }

    @Test
    void liberarReservaDoExemploRespondeReservaLiberadaDentroDoContrato() {
        ObjectNode reservar = (ObjectNode) CONTRATOS.exemplo("ReservarPecas");
        for (JsonNode item : reservar.get("payload").get("itens")) {
            pecas.semear(item.get("pecaInsumoId").asString(), "Peca do exemplo", "80.00", 10);
        }
        consumir(reservar);

        consumir((ObjectNode) CONTRATOS.exemplo("LiberarReserva"));

        assertThat(publicador.tipos()).containsExactly("PecasReservadas", "ReservaLiberada");
        assertThat(errosDoUltimoEvento()).as("ReservaLiberada fora do contrato").isEmpty();
    }
}
