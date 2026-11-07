package br.com.oficina.execution.estoque.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import br.com.oficina.execution.common.infrastructure.dynamodb.PropriedadesDynamo;
import br.com.oficina.execution.common.infrastructure.dynamodb.Tabelas;
import br.com.oficina.execution.common.infrastructure.dynamodb.UnidadeDeTrabalho;
import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import br.com.oficina.execution.estoque.domain.repository.ReservaPecaRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Reservas no DynamoDB (ADR 0006). A leitura e por consulta na chave de particao (a OS); a escrita
 * acrescenta os itens ao lote da {@link UnidadeDeTrabalho}, que confirma de uma vez junto com a
 * resposta no outbox e o registro de idempotencia. Cabem ate 49 pecas por OS (2 itens por peca mais
 * 2 de controle, no limite de 100 da transacao).
 */
@Repository
public class DynamoReservaPecaRepository implements ReservaPecaRepository {

    private final DynamoDbTable<ReservaItem> tabela;
    private final UnidadeDeTrabalho unidade;
    private final String nomeTabelaPecas;
    private final String nomeTabelaReservas;

    public DynamoReservaPecaRepository(DynamoDbEnhancedClient enhanced, UnidadeDeTrabalho unidade, PropriedadesDynamo propriedades) {
        this.nomeTabelaPecas = propriedades.tabela(Tabelas.PECAS);
        this.nomeTabelaReservas = propriedades.tabela(Tabelas.RESERVAS);
        this.tabela = enhanced.table(nomeTabelaReservas, TableSchema.fromBean(ReservaItem.class));
        this.unidade = unidade;
    }

    @Override
    public List<ReservaPeca> buscarPorOrdemDeServico(UUID ordemDeServicoId) {
        return tabela.query(r -> r
                        .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(ordemDeServicoId.toString()).build()))
                        .consistentRead(true))
                .items().stream().map(ReservaItem::paraDominio).toList();
    }

    @Override
    public void reservar(UUID ordemDeServicoId, Map<String, Integer> itens, Instant agora) {
        itens.forEach((pecaId, quantidade) -> {
            unidade.adicionar("peca#" + pecaId,
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.reservarPeca(nomeTabelaPecas, pecaId, quantidade)));
            unidade.adicionar("reserva#" + pecaId,
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.registrarReserva(nomeTabelaReservas,
                            ReservaPeca.nova(ordemDeServicoId, pecaId, quantidade, agora))));
        });
    }

    @Override
    public void liberar(List<ReservaPeca> ativas, Instant agora) {
        for (ReservaPeca reserva : ativas) {
            unidade.adicionar("peca#" + reserva.pecaInsumoId(),
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.devolverPeca(nomeTabelaPecas, reserva.pecaInsumoId(), reserva.quantidade())));
            unidade.adicionar("reserva#" + reserva.pecaInsumoId(),
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.liberarReserva(nomeTabelaReservas, reserva, agora)));
        }
    }

    @Override
    public void consumir(List<ReservaPeca> ativas, Instant agora) {
        for (ReservaPeca reserva : ativas) {
            unidade.adicionar("peca#" + reserva.pecaInsumoId(),
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.consumirPeca(nomeTabelaPecas, reserva.pecaInsumoId(), reserva.quantidade())));
            unidade.adicionar("reserva#" + reserva.pecaInsumoId(),
                    ExpressoesEstoque.emTransacao(ExpressoesEstoque.consumirReserva(nomeTabelaReservas, reserva, agora)));
        }
    }
}
