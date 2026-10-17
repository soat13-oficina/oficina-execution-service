package br.com.oficina.execution.support.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;

/** Duplo das reservas: aplica na hora o que o adapter acrescenta a transacao do DynamoDB. */
public class ReservaPecaRepositoryEmMemoria implements ReservaPecaRepository {

    private final PecaInsumoRepositoryEmMemoria pecas;
    private final Map<String, ReservaPeca> reservas = new LinkedHashMap<>();

    public ReservaPecaRepositoryEmMemoria(PecaInsumoRepositoryEmMemoria pecas) {
        this.pecas = pecas;
    }

    @Override
    public List<ReservaPeca> buscarPorOrdemDeServico(UUID ordemDeServicoId) {
        return reservas.values().stream().filter(r -> r.ordemDeServicoId().equals(ordemDeServicoId)).toList();
    }

    @Override
    public void reservar(UUID ordemDeServicoId, Map<String, Integer> itens, Instant agora) {
        itens.forEach((pecaId, quantidade) -> {
            pecas.reservar(pecaId, quantidade);
            reservas.put(chave(ordemDeServicoId, pecaId), ReservaPeca.nova(ordemDeServicoId, pecaId, quantidade, agora));
        });
    }

    @Override
    public void liberar(List<ReservaPeca> ativas, Instant agora) {
        for (ReservaPeca reserva : new ArrayList<>(ativas)) {
            pecas.devolver(reserva.pecaInsumoId(), reserva.quantidade());
            reservas.put(chave(reserva.ordemDeServicoId(), reserva.pecaInsumoId()),
                    new ReservaPeca(reserva.ordemDeServicoId(), reserva.pecaInsumoId(), reserva.quantidade(),
                            ReservaPeca.Status.LIBERADA, reserva.criadaEm(), agora));
        }
    }

    public void limpar() {
        reservas.clear();
    }

    private static String chave(UUID ordem, String pecaId) {
        return ordem + "/" + pecaId;
    }
}
