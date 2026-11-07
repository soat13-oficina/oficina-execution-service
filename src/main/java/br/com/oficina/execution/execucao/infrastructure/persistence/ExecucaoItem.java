package br.com.oficina.execution.execucao.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import br.com.oficina.execution.execucao.domain.model.Diagnostico;
import br.com.oficina.execution.execucao.domain.model.Etapa;
import br.com.oficina.execution.execucao.domain.model.Execucao;
import br.com.oficina.execution.execucao.domain.model.Mecanico;
import br.com.oficina.execution.execucao.domain.model.PecaDiagnostico;
import br.com.oficina.execution.execucao.domain.model.ServicoDiagnostico;
import br.com.oficina.execution.execucao.domain.model.TransicaoDeEtapa;
import br.com.oficina.execution.execucao.domain.model.Veiculo;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Item da tabela {@code oficina-<env>-execucoes} (ADR 0006): um documento por OS, com o diagnostico, o
 * veiculo e o historico de etapas aninhados. O GSI {@code fila-por-etapa} (chave {@code etapa} +
 * {@code entrouNaEtapaEm}) e a fila do mecanico. A data de entrada na etapa e guardada como texto de
 * largura fixa (microssegundos) porque a ordem do indice e textual: {@code Instant.toString()} corta zeros
 * finais e desordenaria.
 */
@DynamoDbBean
public class ExecucaoItem {

    public static final String INDICE_FILA = "fila-por-etapa";
    /** Microssegundos fixos: a ordem textual e a cronologica. */
    static final DateTimeFormatter ORDEM = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private String ordemServicoId;
    private String sagaId;
    private String numeroOrdemServico;
    private Etapa etapa;
    private String entrouNaEtapaEm;
    private VeiculoDoc veiculo;
    private List<ServicoDoc> servicosPrevistos;
    private List<ItemPecaDoc> pecasPrevistas;
    private DiagnosticoDoc diagnostico;
    private MecanicoDoc mecanicoDiagnostico;
    private MecanicoDoc mecanicoExecucao;
    private List<TransicaoDoc> historico;
    private Instant criadaEm;
    private Instant atualizadaEm;
    private Long versao;

    /** @param versaoAGravar versao que o item leva (a lida mais um, ou 1 na criacao) */
    public static ExecucaoItem de(Execucao e, long versaoAGravar) {
        ExecucaoItem item = new ExecucaoItem();
        item.ordemServicoId = e.ordemServicoId().toString();
        item.sagaId = e.sagaId() == null ? null : e.sagaId().toString();
        item.numeroOrdemServico = e.numeroOrdemServico();
        item.etapa = e.etapa();
        item.entrouNaEtapaEm = ORDEM.format(e.entrouNaEtapaEm());
        item.veiculo = new VeiculoDoc(e.veiculo());
        item.servicosPrevistos = e.servicosPrevistos().stream().map(ServicoDoc::new).toList();
        item.pecasPrevistas = e.pecasPrevistas().stream().map(ItemPecaDoc::new).toList();
        item.diagnostico = e.diagnostico() == null ? null : new DiagnosticoDoc(e.diagnostico());
        item.mecanicoDiagnostico = e.mecanicoDiagnostico() == null ? null : new MecanicoDoc(e.mecanicoDiagnostico());
        item.mecanicoExecucao = e.mecanicoExecucao() == null ? null : new MecanicoDoc(e.mecanicoExecucao());
        item.historico = e.historico().stream().map(TransicaoDoc::new).toList();
        item.criadaEm = e.criadaEm();
        item.atualizadaEm = e.atualizadaEm();
        item.versao = versaoAGravar;
        return item;
    }

    public Execucao paraDominio() {
        return new Execucao(
                UUID.fromString(ordemServicoId),
                sagaId == null ? null : UUID.fromString(sagaId),
                numeroOrdemServico,
                etapa,
                veiculo.paraDominio(),
                servicosPrevistos == null ? List.of() : servicosPrevistos.stream().map(ServicoDoc::paraDominio).toList(),
                pecasPrevistas == null ? List.of() : pecasPrevistas.stream().map(ItemPecaDoc::paraDominio).toList(),
                diagnostico == null ? null : diagnostico.paraDominio(),
                mecanicoDiagnostico == null ? null : mecanicoDiagnostico.paraDominio(),
                mecanicoExecucao == null ? null : mecanicoExecucao.paraDominio(),
                historico.stream().map(TransicaoDoc::paraDominio).toList(),
                Instant.parse(entrouNaEtapaEm),
                criadaEm,
                atualizadaEm,
                versao);
    }

    @DynamoDbPartitionKey
    public String getOrdemServicoId() {
        return ordemServicoId;
    }

    public void setOrdemServicoId(String ordemServicoId) {
        this.ordemServicoId = ordemServicoId;
    }

    public String getSagaId() {
        return sagaId;
    }

    public void setSagaId(String sagaId) {
        this.sagaId = sagaId;
    }

    public String getNumeroOrdemServico() {
        return numeroOrdemServico;
    }

    public void setNumeroOrdemServico(String numeroOrdemServico) {
        this.numeroOrdemServico = numeroOrdemServico;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = INDICE_FILA)
    public Etapa getEtapa() {
        return etapa;
    }

    public void setEtapa(Etapa etapa) {
        this.etapa = etapa;
    }

    @DynamoDbSecondarySortKey(indexNames = INDICE_FILA)
    public String getEntrouNaEtapaEm() {
        return entrouNaEtapaEm;
    }

    public void setEntrouNaEtapaEm(String entrouNaEtapaEm) {
        this.entrouNaEtapaEm = entrouNaEtapaEm;
    }

    public VeiculoDoc getVeiculo() {
        return veiculo;
    }

    public void setVeiculo(VeiculoDoc veiculo) {
        this.veiculo = veiculo;
    }

    public List<ServicoDoc> getServicosPrevistos() {
        return servicosPrevistos;
    }

    public void setServicosPrevistos(List<ServicoDoc> servicosPrevistos) {
        this.servicosPrevistos = servicosPrevistos;
    }

    public List<ItemPecaDoc> getPecasPrevistas() {
        return pecasPrevistas;
    }

    public void setPecasPrevistas(List<ItemPecaDoc> pecasPrevistas) {
        this.pecasPrevistas = pecasPrevistas;
    }

    public DiagnosticoDoc getDiagnostico() {
        return diagnostico;
    }

    public void setDiagnostico(DiagnosticoDoc diagnostico) {
        this.diagnostico = diagnostico;
    }

    public MecanicoDoc getMecanicoDiagnostico() {
        return mecanicoDiagnostico;
    }

    public void setMecanicoDiagnostico(MecanicoDoc mecanicoDiagnostico) {
        this.mecanicoDiagnostico = mecanicoDiagnostico;
    }

    public MecanicoDoc getMecanicoExecucao() {
        return mecanicoExecucao;
    }

    public void setMecanicoExecucao(MecanicoDoc mecanicoExecucao) {
        this.mecanicoExecucao = mecanicoExecucao;
    }

    public List<TransicaoDoc> getHistorico() {
        return historico;
    }

    public void setHistorico(List<TransicaoDoc> historico) {
        this.historico = historico;
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

    /** Lock otimista, conferido por condicao na escrita (ver {@code DynamoExecucaoRepository}). */
    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }

    @DynamoDbBean
    public static class VeiculoDoc {
        private String placa;
        private String marca;
        private String modelo;
        private Integer ano;

        public VeiculoDoc() {
        }

        VeiculoDoc(Veiculo v) {
            this.placa = v.placa();
            this.marca = v.marca();
            this.modelo = v.modelo();
            this.ano = v.ano();
        }

        Veiculo paraDominio() {
            return new Veiculo(placa, marca, modelo, ano);
        }

        public String getPlaca() {
            return placa;
        }

        public void setPlaca(String placa) {
            this.placa = placa;
        }

        public String getMarca() {
            return marca;
        }

        public void setMarca(String marca) {
            this.marca = marca;
        }

        public String getModelo() {
            return modelo;
        }

        public void setModelo(String modelo) {
            this.modelo = modelo;
        }

        public Integer getAno() {
            return ano;
        }

        public void setAno(Integer ano) {
            this.ano = ano;
        }
    }

    @DynamoDbBean
    public static class ServicoDoc {
        private String descricao;
        private BigDecimal valorMaoDeObra;

        public ServicoDoc() {
        }

        ServicoDoc(ServicoDiagnostico s) {
            this.descricao = s.descricao();
            this.valorMaoDeObra = s.valorMaoDeObra();
        }

        ServicoDiagnostico paraDominio() {
            return new ServicoDiagnostico(descricao, valorMaoDeObra);
        }

        public String getDescricao() {
            return descricao;
        }

        public void setDescricao(String descricao) {
            this.descricao = descricao;
        }

        public BigDecimal getValorMaoDeObra() {
            return valorMaoDeObra;
        }

        public void setValorMaoDeObra(BigDecimal valorMaoDeObra) {
            this.valorMaoDeObra = valorMaoDeObra;
        }
    }

    @DynamoDbBean
    public static class ItemPecaDoc {
        private String pecaInsumoId;
        private Integer quantidade;

        public ItemPecaDoc() {
        }

        ItemPecaDoc(PecaDiagnostico p) {
            this.pecaInsumoId = p.pecaInsumoId();
            this.quantidade = p.quantidade();
        }

        PecaDiagnostico paraDominio() {
            return new PecaDiagnostico(pecaInsumoId, quantidade);
        }

        public String getPecaInsumoId() {
            return pecaInsumoId;
        }

        public void setPecaInsumoId(String pecaInsumoId) {
            this.pecaInsumoId = pecaInsumoId;
        }

        public Integer getQuantidade() {
            return quantidade;
        }

        public void setQuantidade(Integer quantidade) {
            this.quantidade = quantidade;
        }
    }

    @DynamoDbBean
    public static class DiagnosticoDoc {
        private String descricao;
        private List<ServicoDoc> servicos;
        private List<ItemPecaDoc> pecas;

        public DiagnosticoDoc() {
        }

        DiagnosticoDoc(Diagnostico d) {
            this.descricao = d.descricao();
            this.servicos = d.servicos().stream().map(ServicoDoc::new).toList();
            this.pecas = d.pecas().stream().map(ItemPecaDoc::new).toList();
        }

        Diagnostico paraDominio() {
            return new Diagnostico(
                    descricao,
                    servicos == null ? List.of() : servicos.stream().map(ServicoDoc::paraDominio).toList(),
                    pecas == null ? List.of() : pecas.stream().map(ItemPecaDoc::paraDominio).toList());
        }

        public String getDescricao() {
            return descricao;
        }

        public void setDescricao(String descricao) {
            this.descricao = descricao;
        }

        public List<ServicoDoc> getServicos() {
            return servicos;
        }

        public void setServicos(List<ServicoDoc> servicos) {
            this.servicos = servicos;
        }

        public List<ItemPecaDoc> getPecas() {
            return pecas;
        }

        public void setPecas(List<ItemPecaDoc> pecas) {
            this.pecas = pecas;
        }
    }

    @DynamoDbBean
    public static class MecanicoDoc {
        private String id;
        private String nome;

        public MecanicoDoc() {
        }

        MecanicoDoc(Mecanico m) {
            this.id = m.id();
            this.nome = m.nome();
        }

        Mecanico paraDominio() {
            return new Mecanico(id, nome);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getNome() {
            return nome;
        }

        public void setNome(String nome) {
            this.nome = nome;
        }
    }

    @DynamoDbBean
    public static class TransicaoDoc {
        private Etapa etapa;
        private Instant em;

        public TransicaoDoc() {
        }

        TransicaoDoc(TransicaoDeEtapa t) {
            this.etapa = t.etapa();
            this.em = t.em();
        }

        TransicaoDeEtapa paraDominio() {
            return new TransicaoDeEtapa(etapa, em);
        }

        public Etapa getEtapa() {
            return etapa;
        }

        public void setEtapa(Etapa etapa) {
            this.etapa = etapa;
        }

        public Instant getEm() {
            return em;
        }

        public void setEm(Instant em) {
            this.em = em;
        }
    }
}
