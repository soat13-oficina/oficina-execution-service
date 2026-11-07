package br.com.oficina.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Sem UserDetailsService o Boot cria um usuario em memoria e escreve a senha no log; a autenticacao aqui e so por JWT.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ExecutionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
