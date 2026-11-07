package br.com.oficina.execution.estoque.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.oficina.execution.common.application.mensageria.Envelope;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.PecaInsumoRepository;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passo {@code ReservarPecas} e compensacao {@code LiberarReserva} da Saga (ADR 0004, passo 4).
 *
 * <p>A reserva e tudo ou nada: com qualquer peca faltando, nada e reservado e a resposta e
 * {@code ReservaFalhou} com as faltantes. Roda dentro da unidade de trabalho do consumidor: a decisao
 * le o estoque, e a escrita - com a condicao {@code disponivel >= quantidade} - so confirma se mais
 * ninguem mexeu na peca nesse intervalo; se mexeu, a transacao e recusada e a mensagem volta para a
 * fila, onde a nova leitura ja enxerga o estoque de agora.
 *
 * <p>Idempotente pelo estado: um comando reenviado por timeout (messageId novo) encontra as reservas
 * da OS e so repete a resposta.
 */
@Service
public class ReservasPelaSaga {
    private static final Logger log = LoggerFactory.getLogger(ReservasPelaSaga.class);

    private final PecaInsumoRepository pecas;
    private final ReservaPecaRepository reservas;
    private final EventosEstoque eventos;
    private final JsonMapper jsonMapper;
    private final Clock relogio;

    @Autowired
    public ReservasPelaSaga(PecaInsumoRepository pecas, ReservaPecaRepository reservas, EventosEstoque eventos, JsonMapper jsonMapper) {
        this(pecas, reservas, eventos, jsonMapper, Clock.systemUTC());
    }

    ReservasPelaSaga(PecaInsumoRepository pecas, ReservaPecaRepository reservas, EventosEstoque eventos, JsonMapper jsonMapper, Clock relogio) {
        this.pecas = pecas;
        this.reservas = reservas;
        this.eventos = eventos;
        this.jsonMapper = jsonMapper;
        this.relogio = relogio;
    }

    public void reservar(Envelope comando) {
        MensagensEstoque.ReservarPecas pedido = jsonMapper.treeToValue(comando.payload(), MensagensEstoque.ReservarPecas.class);
        UUID ordemId = comando.ordemServicoId();
        List<ReservaPeca> ativas = ativas(ordemId);
        if (!ativas.isEmpty()) {
            log.info("Pecas ja reservadas para a OS; resposta repetida. ordemServicoId={}", ordemId);
            eventos.responder(comando, MensagensEstoque.PECAS_RESERVADAS,
                    new MensagensEstoque.PecasReservadas(pedido.orcamentoId(), itens(ativas)));
            return;
        }

        // Ordenado por id: duas OSs com as mesmas pecas conflitam sempre na mesma ordem.
        Map<String, Integer> solicitadas = new TreeMap<>();
        pedido.itens().forEach(item -> solicitadas.merge(item.pecaInsumoId(), item.quantidade(), Integer::sum));

        List<MensagensEstoque.PecaFaltante> faltantes = new ArrayList<>();
        solicitadas.forEach((pecaId, quantidade) -> {
            Optional<PecaInsumo> peca = pecas.buscarPorId(pecaId);
            int disponivel = peca.map(PecaInsumo::getQuantidadeDisponivel).orElse(0);
            if (disponivel < quantidade) {
                faltantes.add(new MensagensEstoque.PecaFaltante(pecaId, quantidade, disponivel));
            }
        });
        if (!faltantes.isEmpty()) {
            log.info("Reserva recusada por estoque insuficiente. ordemServicoId={}, faltantes={}", ordemId, faltantes);
            eventos.responder(comando, MensagensEstoque.RESERVA_FALHOU,
                    new MensagensEstoque.ReservaFalhou(pedido.orcamentoId(), faltantes));
            return;
        }

        reservas.reservar(ordemId, solicitadas, Instant.now(relogio));
        log.info("Pecas reservadas para a OS. ordemServicoId={}, itens={}", ordemId, solicitadas);
        List<MensagensEstoque.ItemPeca> reservadas = solicitadas.entrySet().stream()
                .map(e -> new MensagensEstoque.ItemPeca(e.getKey(), e.getValue()))
                .toList();
        eventos.responder(comando, MensagensEstoque.PECAS_RESERVADAS,
                new MensagensEstoque.PecasReservadas(pedido.orcamentoId(), reservadas));
    }

    /** Compensacao: devolve ao disponivel o que esta reservado para a OS. Sem reserva, confirma com lista vazia. */
    public void liberar(Envelope comando) {
        MensagensEstoque.LiberarReserva pedido = jsonMapper.treeToValue(comando.payload(), MensagensEstoque.LiberarReserva.class);
        List<ReservaPeca> ativas = ativas(comando.ordemServicoId());
        if (!ativas.isEmpty()) {
            reservas.liberar(ativas, Instant.now(relogio));
        }
        log.info("Reserva liberada pela Saga. ordemServicoId={}, itens={}, motivo={}",
                comando.ordemServicoId(), ativas.size(), pedido.motivo());
        eventos.responder(comando, MensagensEstoque.RESERVA_LIBERADA, new MensagensEstoque.ReservaLiberada(itens(ativas)));
    }

    private List<ReservaPeca> ativas(UUID ordemId) {
        return reservas.buscarPorOrdemDeServico(ordemId).stream().filter(ReservaPeca::ativa).toList();
    }

    private static List<MensagensEstoque.ItemPeca> itens(List<ReservaPeca> reservas) {
        return reservas.stream().map(r -> new MensagensEstoque.ItemPeca(r.pecaInsumoId(), r.quantidade())).toList();
    }
}
