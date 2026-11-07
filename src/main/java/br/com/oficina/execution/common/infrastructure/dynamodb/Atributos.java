package br.com.oficina.execution.common.infrastructure.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Atalhos para montar {@link AttributeValue} nas expressoes do cliente de baixo nivel. */
public final class Atributos {

    private Atributos() {
    }

    public static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    public static AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
