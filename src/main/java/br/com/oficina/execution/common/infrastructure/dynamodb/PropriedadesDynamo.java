package br.com.oficina.execution.common.infrastructure.dynamodb;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acesso ao DynamoDB (ADR 0006). As tabelas sao criadas pelo Terraform do {@code infra/} e seguem a
 * convencao {@code oficina-<ambiente>-<nome>}; a aplicacao nunca cria tabela.
 *
 * @param ambiente {@code hml}, {@code prd} ou {@code local}
 * @param endpoint endpoint alternativo (LocalStack); vazio = AWS
 * @param regiao   regiao AWS
 */
@ConfigurationProperties("dynamodb")
public record PropriedadesDynamo(String ambiente, URI endpoint, String regiao) {

    public PropriedadesDynamo {
        ambiente = ambiente == null || ambiente.isBlank() ? "local" : ambiente;
        regiao = regiao == null || regiao.isBlank() ? "us-east-1" : regiao;
    }

    /** Nome fisico da tabela a partir do nome logico (ver {@link Tabelas}). */
    public String tabela(String nomeLogico) {
        return "oficina-" + ambiente + "-" + nomeLogico;
    }
}
