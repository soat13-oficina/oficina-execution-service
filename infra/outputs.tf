output "environment" {
  description = "Ambiente correspondente ao workspace selecionado (hml ou prd)."
  value       = local.environment
}

output "irsa_role_arn" {
  description = "Role do pod (anotacao eks.amazonaws.com/role-arn da ServiceAccount)."
  value       = aws_iam_role.servico.arn
}

output "ecr_repository_url" {
  description = "Repositorio das imagens da Execution neste ambiente."
  value       = aws_ecr_repository.servico.repository_url
}

output "tabelas" {
  description = "Nomes fisicos das tabelas do DynamoDB - contrato com a pipeline (seed) e com PropriedadesDynamo."
  value = {
    pecas     = aws_dynamodb_table.pecas.name
    reservas  = aws_dynamodb_table.reservas.name
    execucoes = aws_dynamodb_table.execucoes.name
    controle  = aws_dynamodb_table.controle.name
  }
}

output "fila_execution_commands" {
  description = "URL da fila que a Execution consome."
  value       = aws_sqs_queue.execution_commands.url
}

output "fila_execution_commands_dlq" {
  description = "URL da DLQ dos comandos da Execution."
  value       = aws_sqs_queue.execution_commands_dlq.url
}
