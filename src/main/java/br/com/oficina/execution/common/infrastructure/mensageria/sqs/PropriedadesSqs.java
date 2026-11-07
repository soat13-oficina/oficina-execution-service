package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mensageria por Amazon SQS FIFO (ADR 0005). A Execution publica tudo em {@code os-saga-replies} e le
 * {@code execution-commands}; sem SQS habilitado nada sai do outbox (as mensagens ficam pendentes).
 *
 * @param habilitado    cria o cliente SQS, o transporte e o receptor
 * @param consumo       filas logicas que este servico le do SQS
 * @param ambiente      {@code hml}, {@code prd} ou {@code local}: nome fisico {@code oficina-<ambiente>-<fila>.fifo}
 * @param endpoint      endpoint alternativo (LocalStack); vazio = AWS
 * @param regiao        regiao AWS
 * @param espera        long polling do receive (maximo do SQS: 20 s)
 * @param maxMensagens  mensagens por receive (1 a 10)
 * @param pausaAposErro espera do receptor depois de erro de rede/AWS, antes de tentar de novo
 * @param receptorAutomatico inicia o receptor com a aplicacao (os testes desligam e chamam o lote direto)
 */
@ConfigurationProperties("mensageria.sqs")
public record PropriedadesSqs(
        boolean habilitado,
        Set<String> consumo,
        String ambiente,
        URI endpoint,
        String regiao,
        Duration espera,
        int maxMensagens,
        Duration pausaAposErro,
        Boolean receptorAutomatico) {

    public PropriedadesSqs {
        consumo = consumo == null ? Set.of() : Set.copyOf(consumo);
        ambiente = ambiente == null || ambiente.isBlank() ? "local" : ambiente;
        regiao = regiao == null || regiao.isBlank() ? "us-east-1" : regiao;
        espera = espera == null ? Duration.ofSeconds(20) : espera;
        maxMensagens = maxMensagens <= 0 ? 10 : Math.min(maxMensagens, 10);
        pausaAposErro = pausaAposErro == null ? Duration.ofSeconds(5) : pausaAposErro;
        receptorAutomatico = receptorAutomatico == null || receptorAutomatico;
    }

    /** Convencao de nomes (docs/arquitetura/convencoes-de-nomes.md). */
    public String nomeFila(String filaLogica) {
        return "oficina-" + ambiente + "-" + filaLogica + ".fifo";
    }

    public String nomeDlq(String filaLogica) {
        return "oficina-" + ambiente + "-" + filaLogica + "-dlq.fifo";
    }
}
