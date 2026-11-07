package br.com.oficina.execution.common.infrastructure.mensageria;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga o {@code @Scheduled} do publicador do outbox. */
@Configuration
@EnableScheduling
public class AgendamentoConfig {
}
