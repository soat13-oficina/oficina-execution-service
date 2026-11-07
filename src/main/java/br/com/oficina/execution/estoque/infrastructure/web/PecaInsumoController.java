package br.com.oficina.execution.estoque.infrastructure.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import br.com.oficina.execution.estoque.application.CatalogoDePecasService;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService.DadosPeca;
import br.com.oficina.execution.estoque.application.CatalogoDePecasService.Filtro;
import br.com.oficina.execution.estoque.domain.model.CategoriaPeca;
import br.com.oficina.execution.estoque.infrastructure.web.request.AdicionarEstoquePecaRequest;
import br.com.oficina.execution.estoque.infrastructure.web.request.AlterarPecaInsumoRequest;
import br.com.oficina.execution.estoque.infrastructure.web.request.CadastrarPecaInsumoRequest;
import br.com.oficina.execution.estoque.infrastructure.web.request.RemoverEstoquePecaRequest;
import br.com.oficina.execution.estoque.infrastructure.web.response.PecaInsumoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Catalogo e estoque de pecas e insumos - mesmos caminhos do modulo {@code pecainsumo} do OS Service.
 * Somente funcionarios (ver {@code SecurityConfig}). Reservar, liberar e consumir pecas nao tem
 * endpoint: sao passos da Saga, para que toda reserva tenha uma OS dona.
 */
@RestController
@RequestMapping("/pecas-insumos")
@Tag(name = "Pecas e insumos", description = "Catalogo e estoque de pecas (somente funcionarios)")
public class PecaInsumoController {

    private final CatalogoDePecasService catalogo;

    public PecaInsumoController(CatalogoDePecasService catalogo) {
        this.catalogo = catalogo;
    }

    @Operation(summary = "Cadastrar peca/insumo")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cadastrada; Location aponta para a peca", content = @Content),
            @ApiResponse(responseCode = "400", description = "Dados invalidos", content = @Content)
    })
    @PostMapping
    public ResponseEntity<Void> cadastrar(@Valid @RequestBody CadastrarPecaInsumoRequest request) {
        String id = catalogo.cadastrar(new DadosPeca(
                request.descricao(), request.marca(), request.preco(), request.quantidadeEstoque(),
                request.codigoReferencia(), request.categoria()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.created(location).build();
    }

    @Operation(summary = "Alterar peca/insumo",
            description = "A quantidade reservada pertence as reservas das OSs e nao muda por aqui; o estoque novo nao pode ficar abaixo dela.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Alterada", content = @Content),
            @ApiResponse(responseCode = "400", description = "Dados invalidos ou estoque abaixo do reservado", content = @Content),
            @ApiResponse(responseCode = "404", description = "Peca nao encontrada", content = @Content),
            @ApiResponse(responseCode = "409", description = "Alterada por outra requisicao no meio do caminho", content = @Content)
    })
    @PutMapping("/{id}")
    public ResponseEntity<Void> alterar(@PathVariable String id, @Valid @RequestBody AlterarPecaInsumoRequest request) {
        catalogo.alterar(id, new DadosPeca(
                request.descricao(), request.marca(), request.preco(), request.quantidadeEstoque(),
                request.codigoReferencia(), request.categoria()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Buscar peca/insumo por id")
    @GetMapping("/{id}")
    public ResponseEntity<PecaInsumoResponse> buscarPorId(@PathVariable String id) {
        return ResponseEntity.ok(PecaInsumoResponse.from(catalogo.buscar(id)));
    }

    @Operation(summary = "Listar pecas/insumos", description = "Filtros opcionais por marca, categoria e presenca de reserva.")
    @GetMapping
    public ResponseEntity<List<PecaInsumoResponse>> listar(
            @RequestParam(required = false) String marca,
            @RequestParam(required = false) CategoriaPeca categoria,
            @RequestParam(required = false) Boolean possuiReserva) {
        return ResponseEntity.ok(catalogo.listar(new Filtro(marca, categoria, possuiReserva)).stream()
                .map(PecaInsumoResponse::from)
                .toList());
    }

    @Operation(summary = "Excluir peca/insumo", description = "Recusada enquanto houver reserva ativa.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable String id) {
        catalogo.excluir(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Adicionar estoque")
    @PostMapping("/{id}/adicionar-estoque")
    public ResponseEntity<Void> adicionarEstoque(@PathVariable String id, @Valid @RequestBody AdicionarEstoquePecaRequest request) {
        catalogo.adicionarEstoque(id, request.quantidade());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remover estoque", description = "So do que esta disponivel; o reservado pertence a uma OS.")
    @PostMapping("/{id}/remover-estoque")
    public ResponseEntity<Void> removerEstoque(@PathVariable String id, @Valid @RequestBody RemoverEstoquePecaRequest request) {
        catalogo.removerEstoque(id, request.quantidade());
        return ResponseEntity.noContent().build();
    }
}
