package br.com.oficina.execution.common.infrastructure.observabilidade;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aplica {@code ambiente} como tag comum a todas as metricas, como no OS Service: um mesmo
 * dashboard separa hml de prd sem duplicar widget.
 */
@Configuration
public class MetricasCommonTagsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ambienteCommonTag(
            @Value("${observabilidade.ambiente:local}") String ambiente) {
        return registry -> registry.config().commonTags("ambiente", ambiente);
    }
}
