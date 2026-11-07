package br.com.oficina.execution.common.infrastructure.mensageria.sqs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/** Duplo do {@link SqsClient} (interface do SDK): so as operacoes que o transporte usa. */
class SqsClientFalso implements SqsClient {

    final List<SendMessageRequest> enviadas = new ArrayList<>();
    final List<DeleteMessageRequest> apagadas = new ArrayList<>();
    final List<GetQueueUrlRequest> consultasDeUrl = new ArrayList<>();
    final Deque<Supplier<ReceiveMessageResponse>> respostas = new ArrayDeque<>();
    volatile int receives;

    @Override
    public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
        consultasDeUrl.add(request);
        return GetQueueUrlResponse.builder().queueUrl("https://sqs.local/" + request.queueName()).build();
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        enviadas.add(request);
        return SendMessageResponse.builder().messageId("m-" + enviadas.size()).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
        apagadas.add(request);
        return DeleteMessageResponse.builder().build();
    }

    @Override
    public synchronized ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        receives++;
        Supplier<ReceiveMessageResponse> proxima = respostas.poll();
        return proxima == null ? ReceiveMessageResponse.builder().build() : proxima.get();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
    }
}
