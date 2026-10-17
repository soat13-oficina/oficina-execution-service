# Fila que a Execution CONSOME: comandos do orquestrador da Saga (EnfileirarDiagnostico,
# ReabrirDiagnostico, ReservarPecas, EnfileirarExecucao, LiberarReserva, CancelarExecucao). Cada servico
# cria as filas que consome; o OS so precisa do nome.
#
# FIFO com MessageGroupId = ordemServicoId e MessageDeduplicationId = messageId, enviados pelo OS.

resource "aws_sqs_queue" "execution_commands_dlq" {
  name                      = "${local.prefixo_filas}-execution-commands-dlq.fifo"
  fifo_queue                = true
  message_retention_seconds = local.cfg.retencao_dlq_segundos
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "execution_commands" {
  name                        = "${local.prefixo_filas}-execution-commands.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  message_retention_seconds   = local.cfg.retencao_mensagens_segundos
  sqs_managed_sse_enabled     = true

  # 60 s para processar uma transacao do DynamoDB com folga; long polling de 20 s.
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20

  # Na 5a falha o SQS move para a DLQ. Fora do contrato a aplicacao manda direto (ReceptorSqs).
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.execution_commands_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "execution_commands_dlq" {
  queue_url = aws_sqs_queue.execution_commands_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.execution_commands.arn]
  })
}
