# Role do pod da Execution via IRSA: consumir execution-commands, responder em os-saga-replies e
# ler/escrever as quatro tabelas do DynamoDB.
#
# Nome previsivel - contrato com a pipeline (e com k8s/overlays/*/patch-serviceaccount.yaml):
#   aws iam get-role --role-name oficina-<env>-oficina-execution-service

data "aws_iam_policy_document" "trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.platform.oidc_provider_arn]
    }

    # So a ServiceAccount deste servico, no namespace deste ambiente.
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider}:sub"
      values   = ["system:serviceaccount:${local.namespace}:${var.service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "servico" {
  name               = "${var.project}-${local.environment}-${var.servico}"
  description        = "Execution Service (${local.environment}): SQS da Saga e DynamoDB, assumida via IRSA"
  assume_role_policy = data.aws_iam_policy_document.trust.json
}

data "aws_iam_policy_document" "servico" {
  statement {
    sid = "ConsomeComandosDaSaga"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.execution_commands.arn]
  }

  # O ReceptorSqs manda direto para a DLQ o que esta fora do contrato.
  statement {
    sid       = "EnviaParaAPropriaDlq"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
    resources = [aws_sqs_queue.execution_commands_dlq.arn]
  }

  # os-saga-replies e criada pelo infra/ do OS Service: ARN pelo nome, sem ler o state de la.
  statement {
    sid       = "RespondeAoOrquestrador"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
    resources = ["arn:aws:sqs:${var.aws_region}:${local.conta_id}:${local.prefixo_filas}-os-saga-replies.fifo"]
  }

  # TransactWriteItems exige a permissao de cada operacao que a transacao faz (Put, Update,
  # ConditionCheck); DescribeTable e o readiness probe (DynamoDbHealthIndicator).
  statement {
    sid = "TabelasDaExecution"
    actions = [
      "dynamodb:DescribeTable",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Query",
      "dynamodb:Scan",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:ConditionCheckItem",
    ]
    resources = flatten([
      for tabela in [
        aws_dynamodb_table.pecas,
        aws_dynamodb_table.reservas,
        aws_dynamodb_table.execucoes,
        aws_dynamodb_table.controle,
      ] : [tabela.arn, "${tabela.arn}/index/*"]
    ])
  }
}

resource "aws_iam_role_policy" "servico" {
  name   = "${var.project}-${local.environment}-${var.servico}"
  role   = aws_iam_role.servico.id
  policy = data.aws_iam_policy_document.servico.json
}
