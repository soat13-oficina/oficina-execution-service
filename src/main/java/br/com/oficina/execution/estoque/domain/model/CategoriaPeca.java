package br.com.oficina.execution.estoque.domain.model;

public enum CategoriaPeca {
    FILTROS(1, "Filtros"),
    FREIOS(2, "Freios"),
    LUBRIFICANTES(3, "Lubrificantes"),
    ELETRICA(4, "Elétrica"),
    TRANSMISSAO(5, "Transmissão"),
    IGNICAO(6, "Ignição"),
    SUSPENSAO(7, "Suspensão"),
    ARREFECIMENTO(8, "Arrefecimento"),
    COMBUSTIVEL(9, "Combustível"),
    ESCAPAMENTO(10, "Escapamento");

    private final int codigo;
    private final String descricao;

    CategoriaPeca(int codigo, String descricao) {
        this.codigo = codigo;
        this.descricao = descricao;
    }

    public int getCodigo() {
        return codigo;
    }

    public String getDescricao() {
        return descricao;
    }
}
