package br.com.oficina.execution.support.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import br.com.oficina.execution.support.mensageria.PublicadorMensagensEmMemoria;

/**
 * Troca os adapters do DynamoDB, o outbox e a transacao pelos duplos em memoria nos testes que sobem o
 * contexto Spring sem AWS: o que se confere ali e a camada web, a seguranca e a fiacao dos servicos.
 */
@TestConfiguration
public class EstoqueEmMemoriaConfig {

    @Bean
    @Primary
    public PecaInsumoRepositoryEmMemoria pecasEmMemoria() {
        return new PecaInsumoRepositoryEmMemoria();
    }

    @Bean
    @Primary
    public ReservaPecaRepositoryEmMemoria reservasEmMemoria(PecaInsumoRepositoryEmMemoria pecas) {
        return new ReservaPecaRepositoryEmMemoria(pecas);
    }

    @Bean
    @Primary
    public ExecucaoRepositoryEmMemoria execucoesEmMemoria() {
        return new ExecucaoRepositoryEmMemoria();
    }

    @Bean
    @Primary
    public TransacaoEmMemoria transacaoEmMemoria() {
        return new TransacaoEmMemoria();
    }

    @Bean
    @Primary
    public PublicadorMensagensEmMemoria publicadorEmMemoria() {
        return new PublicadorMensagensEmMemoria();
    }
}
