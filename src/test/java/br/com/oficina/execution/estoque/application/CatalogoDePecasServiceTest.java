package br.com.oficina.execution.estoque.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.oficina.execution.common.domain.exception.ConflitoDeRecursoException;
import br.com.oficina.execution.common.domain.exception.RecursoNaoEncontradoException;
import br.com.oficina.execution.common.domain.exception.RegraDeNegocioException;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService.DadosPeca;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService.Filtro;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.domain.model.PecaInsumo;
import br.com.oficina.execution.support.persistence.PecaInsumoRepositoryEmMemoria;

class CatalogoDePecasServiceTest {

    private PecaInsumoRepositoryEmMemoria repositorio;
    private CatalogoDePecasService servico;

    @BeforeEach
    void preparar() {
        repositorio = new PecaInsumoRepositoryEmMemoria();
        servico = new CatalogoDePecasService(repositorio);
    }

    private static DadosPeca dados(String descricao, String marca, int estoque, CategoriaPeca categoria) {
        return new DadosPeca(descricao, marca, new BigDecimal("45.90"), estoque, "REF-" + descricao, categoria);
    }

    @Test
    void cadastraEBuscaPeloIdGerado() {
        String id = servico.cadastrar(dados("Filtro", "Bosch", 10, CategoriaPeca.FILTROS));

        PecaInsumo peca = servico.buscar(id);
        assertThat(peca.getDescricao()).isEqualTo("Filtro");
        assertThat(peca.getQuantidadeDisponivel()).isEqualTo(10);
    }

    @Test
    void buscarInexistenteResponde404() {
        assertThatThrownBy(() -> servico.buscar("nao-existe")).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void alterarCorrigeOsDadosEMantemAQuantidadeReservada() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);
        repositorio.reservar("p-1", 4);

        servico.alterar("p-1", dados("Filtro novo", "Mann", 12, CategoriaPeca.FREIOS));

        PecaInsumo alterada = servico.buscar("p-1");
        assertThat(alterada.getDescricao()).isEqualTo("Filtro novo");
        assertThat(alterada.getQuantidadeEstoque()).isEqualTo(12);
        assertThat(alterada.getQuantidadeReservada()).isEqualTo(4);
        assertThat(alterada.getQuantidadeDisponivel()).isEqualTo(8);
    }

    @Test
    void alterarNaoDeixaOEstoqueAbaixoDoReservado() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);
        repositorio.reservar("p-1", 4);

        assertThatThrownBy(() -> servico.alterar("p-1", dados("Filtro", "Bosch", 3, CategoriaPeca.FILTROS)))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("nao pode exceder o estoque");
    }

    @Test
    void alterarInexistenteResponde404() {
        assertThatThrownBy(() -> servico.alterar("nao-existe", dados("Filtro", "Bosch", 3, CategoriaPeca.FILTROS)))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void escritaConcorrenteViraConflito() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);
        PecaInsumo lida = servico.buscar("p-1");
        repositorio.adicionarEstoque("p-1", 1); // outra requisicao mexe depois da leitura

        assertThatThrownBy(() -> repositorio.substituir(lida)).isInstanceOf(ConflitoDeRecursoException.class);
    }

    @Test
    void listaComFiltros() {
        servico.cadastrar(dados("Filtro", "Bosch", 10, CategoriaPeca.FILTROS));
        servico.cadastrar(dados("Pastilha", "Bosch", 5, CategoriaPeca.FREIOS));
        servico.cadastrar(dados("Disco", "TRW", 5, CategoriaPeca.FREIOS));
        repositorio.reservar(repositorio.buscarTodos().get(1).getId(), 2);

        assertThat(servico.listar(new Filtro(null, null, null))).hasSize(3);
        assertThat(servico.listar(new Filtro("Bosch", null, null))).extracting(PecaInsumo::getDescricao).containsExactly("Filtro", "Pastilha");
        assertThat(servico.listar(new Filtro(null, CategoriaPeca.FREIOS, null))).extracting(PecaInsumo::getDescricao).containsExactly("Pastilha", "Disco");
        assertThat(servico.listar(new Filtro(null, null, true))).extracting(PecaInsumo::getDescricao).containsExactly("Pastilha");
        assertThat(servico.listar(new Filtro(null, null, false))).extracting(PecaInsumo::getDescricao).containsExactly("Filtro", "Disco");
        assertThat(servico.listar(new Filtro("TRW", CategoriaPeca.FILTROS, null))).isEmpty();
    }

    @Test
    void excluiPecaSemReserva() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);

        servico.excluir("p-1");

        assertThat(repositorio.buscarPorId("p-1")).isEmpty();
    }

    @Test
    void naoExcluiPecaComReservaAtiva() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);
        repositorio.reservar("p-1", 1);

        assertThatThrownBy(() -> servico.excluir("p-1"))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("reserva ativa");
        assertThat(repositorio.buscarPorId("p-1")).isPresent();
    }

    @Test
    void excluirInexistenteResponde404() {
        assertThatThrownBy(() -> servico.excluir("nao-existe")).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void entradaESaidaDeEstoqueMantemODisponivel() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);

        servico.adicionarEstoque("p-1", 5);
        servico.removerEstoque("p-1", 3);

        PecaInsumo peca = servico.buscar("p-1");
        assertThat(peca.getQuantidadeEstoque()).isEqualTo(12);
        assertThat(peca.getQuantidadeDisponivel()).isEqualTo(12);
    }

    @Test
    void saidaSoDoDisponivel() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);
        repositorio.reservar("p-1", 8);

        assertThatThrownBy(() -> servico.removerEstoque("p-1", 3))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("insuficiente");
    }

    @Test
    void quantidadeDeEstoqueTemQueSerPositiva() {
        repositorio.semear("p-1", "Filtro", "10.00", 10);

        assertThatThrownBy(() -> servico.adicionarEstoque("p-1", 0))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("adicionada");
        assertThatThrownBy(() -> servico.removerEstoque("p-1", -2))
                .isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("removida");
    }

    @Test
    void consultaDevolveEncontradasNaOrdemPedidaEOsInexistentes() {
        repositorio.semear("a", "Filtro", "10.00", 1);
        repositorio.semear("b", "Pastilha", "20.00", 1);

        var resultado = servico.consultar(List.of("b", "nao-existe", "a", "b", " "));

        assertThat(resultado.encontradas()).extracting(PecaInsumo::getId).containsExactly("b", "a");
        assertThat(resultado.inexistentes()).containsExactly("nao-existe", " ");
    }

    @Test
    void consultaExigeDeUmADuzentosIds() {
        assertThatThrownBy(() -> servico.consultar(null)).isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> servico.consultar(List.of())).isInstanceOf(RegraDeNegocioException.class);

        List<String> muitos = new ArrayList<>();
        for (int i = 0; i <= CatalogoDePecasService.MAXIMO_IDS_POR_CONSULTA; i++) {
            muitos.add("id-" + i);
        }
        assertThatThrownBy(() -> servico.consultar(muitos))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("200");
    }
}
