package br.com.oficina.execution.common.infrastructure.mensageria;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Estado do outbox (ADR 0005) como gauges, para o alerta de "mensagem parada" - os mesmos nomes do OS e do
 * Billing, para um monitor so:
 *
 * <ul>
 *   <li>{@code oficina.outbox.pendentes} - esperando entrega (itens do GSI esparso {@code outbox-pendente});</li>
 *   <li>{@code oficina.outbox.idade.maxima} - em segundos, a mais antiga delas;</li>
 *   <li>{@code oficina.outbox.esgotadas} - desistiu de tentar. <b>Diferente do OS e do Billing</b>, aqui conta
 *       as esgotadas <i>desde a subida do pod</i>: o item esgotado sai do indice e o DynamoDB nao tem
 *       contagem barata fora dele. Basta para alertar (o monitor olha uma janela), mas zera num restart.</li>
 * </ul>
 */
@Component
public class MetricasOutbox {

    @Autowired
    public MetricasOutbox(MeterRegistry registry, OutboxDynamo outbox) {
        this(registry, outbox, Clock.systemUTC());
    }

    MetricasOutbox(MeterRegistry registry, OutboxDynamo outbox, Clock relogio) {
        Gauge.builder("oficina.outbox.pendentes", outbox::contarPendentes)
                .description("Mensagens do outbox esperando entrega")
                .register(registry);
        Gauge.builder("oficina.outbox.esgotadas", outbox::esgotadasDesdeAPartida)
                .description("Mensagens do outbox que esgotaram as tentativas de envio desde a subida do pod")
                .register(registry);
        Gauge.builder("oficina.outbox.idade.maxima", () -> outbox.maisAntigaPendente()
                        .map(criada -> (double) Math.max(0, Duration.between(criada, relogio.instant()).toSeconds()))
                        .orElse(0.0))
                .description("Idade, em segundos, da mensagem pendente mais antiga")
                .baseUnit("seconds")
                .register(registry);
    }
}
