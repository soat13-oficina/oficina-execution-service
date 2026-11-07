package br.com.oficina.execution.estoque.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;

class PecaInsumoTest {

    private static PecaInsumo peca(int estoque, int reservada) {
        return new PecaInsumo("p-1", "Filtro", "Bosch", new BigDecimal("45.90"), estoque, reservada, "REF", CategoriaPeca.FILTROS, 3L);
    }

    @Test
    void disponivelEEstoqueMenosReservada() {
        assertThat(peca(10, 4).getQuantidadeDisponivel()).isEqualTo(6);
    }

    @Test
    void pecaNovaTemIdGeradoNadaReservadoESemVersao() {
        PecaInsumo nova = PecaInsumo.nova("Filtro", "Bosch", new BigDecimal("45.90"), 7, "REF", CategoriaPeca.FILTROS);

        assertThat(nova.getId()).isNotBlank();
        assertThat(nova.getQuantidadeReservada()).isZero();
        assertThat(nova.getQuantidadeDisponivel()).isEqualTo(7);
        assertThat(nova.getVersao()).isNull();
    }

    @Test
    void alterarMantemIdEVersao() {
        PecaInsumo alterada = peca(10, 4).alterar("Filtro de ar", "Mann", new BigDecimal("50.00"), 12, 4, "REF-2", CategoriaPeca.ARREFECIMENTO);

        assertThat(alterada.getId()).isEqualTo("p-1");
        assertThat(alterada.getVersao()).isEqualTo(3L);
        assertThat(alterada.getDescricao()).isEqualTo("Filtro de ar");
        assertThat(alterada.getMarca()).isEqualTo("Mann");
        assertThat(alterada.getPreco()).isEqualByComparingTo("50.00");
        assertThat(alterada.getQuantidadeEstoque()).isEqualTo(12);
        assertThat(alterada.getCodigoReferencia()).isEqualTo("REF-2");
        assertThat(alterada.getCategoria()).isEqualTo(CategoriaPeca.ARREFECIMENTO);
    }

    @Test
    void reservadaNaoPodeExcederOEstoque() {
        assertThatThrownBy(() -> peca(3, 4))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("reservada (4)")
                .hasMessageContaining("estoque (3)");
    }

    @Test
    void quantidadesNaoPodemSerNegativas() {
        assertThatThrownBy(() -> peca(-1, 0)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> peca(5, -1)).isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    void categoriaExpoeCodigoEDescricao() {
        assertThat(CategoriaPeca.FREIOS.getCodigo()).isEqualTo(2);
        assertThat(CategoriaPeca.ELETRICA.getDescricao()).isEqualTo("Elétrica");
    }

    @Test
    void reservaAtivaSoQuandoReservada() {
        java.time.Instant agora = java.time.Instant.parse("2026-10-17T12:00:00Z");
        ReservaPeca nova = ReservaPeca.nova(java.util.UUID.randomUUID(), "p-1", 2, agora);

        assertThat(nova.ativa()).isTrue();
        assertThat(new ReservaPeca(nova.ordemDeServicoId(), "p-1", 2, ReservaPeca.Status.LIBERADA, agora, agora).ativa()).isFalse();
        assertThat(new ReservaPeca(nova.ordemDeServicoId(), "p-1", 2, ReservaPeca.Status.CONSUMIDA, agora, agora).ativa()).isFalse();
    }
}
