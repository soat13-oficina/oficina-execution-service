package br.com.oficina.execution.estoque.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import br.com.oficina.execution.estoque.domain.model.ReservaPeca;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/** Item da tabela {@code oficina-<env>-reservas}: chave {@code ordemServicoId} + {@code pecaId}. */
@DynamoDbBean
public class ReservaItem {
    private String ordemServicoId;
    private String pecaId;
    private Integer quantidade;
    private String status;
    private Instant criadaEm;
    private Instant atualizadaEm;

    public static ReservaItem de(ReservaPeca reserva) {
        ReservaItem item = new ReservaItem();
        item.ordemServicoId = reserva.ordemDeServicoId().toString();
        item.pecaId = reserva.pecaInsumoId();
        item.quantidade = reserva.quantidade();
        item.status = reserva.status().name();
        item.criadaEm = reserva.criadaEm();
        item.atualizadaEm = reserva.atualizadaEm();
        return item;
    }

    public ReservaPeca paraDominio() {
        return new ReservaPeca(UUID.fromString(ordemServicoId), pecaId, quantidade, ReservaPeca.Status.valueOf(status), criadaEm, atualizadaEm);
    }

    @DynamoDbPartitionKey
    public String getOrdemServicoId() {
        return ordemServicoId;
    }

    public void setOrdemServicoId(String ordemServicoId) {
        this.ordemServicoId = ordemServicoId;
    }

    @DynamoDbSortKey
    public String getPecaId() {
        return pecaId;
    }

    public void setPecaId(String pecaId) {
        this.pecaId = pecaId;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public void setCriadaEm(Instant criadaEm) {
        this.criadaEm = criadaEm;
    }

    public Instant getAtualizadaEm() {
        return atualizadaEm;
    }

    public void setAtualizadaEm(Instant atualizadaEm) {
        this.atualizadaEm = atualizadaEm;
    }
}
