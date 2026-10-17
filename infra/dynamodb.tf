# Tabelas do Execution Service (ADR 0006): DynamoDB sob demanda (PAY_PER_REQUEST), sem capacidade para
# dimensionar nem servidor para operar. A aplicacao nunca cria tabela; o desenho abaixo tem fonte unica
# aqui e e espelhado em scripts/criar-tabelas-local.sh (LocalStack) e em
# src/test/.../TabelasDeTeste.java (Testcontainers).

# Catalogo e estoque. disponivel = estoque - reservada e guardado explicitamente: o DynamoDB nao aceita
# aritmetica em ConditionExpression (ver PecaItem).
resource "aws_dynamodb_table" "pecas" {
  name         = "${local.prefixo_tabelas}-pecas"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pecaId"

  attribute {
    name = "pecaId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = local.cfg.point_in_time_recovery
  }
}

# Reservas de pecas por OS: uma linha por OS + peca (RESERVADA, CONSUMIDA ou LIBERADA).
resource "aws_dynamodb_table" "reservas" {
  name         = "${local.prefixo_tabelas}-reservas"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "ordemServicoId"
  range_key    = "pecaId"

  attribute {
    name = "ordemServicoId"
    type = "S"
  }

  attribute {
    name = "pecaId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = local.cfg.point_in_time_recovery
  }
}

# Execucoes: um documento por OS (diagnostico e historico aninhados). O GSI e a fila do mecanico: etapa
# (AGUARDANDO_DIAGNOSTICO, AGUARDANDO_EXECUCAO...) + data de entrada na etapa, em texto de largura fixa.
resource "aws_dynamodb_table" "execucoes" {
  name         = "${local.prefixo_tabelas}-execucoes"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "ordemServicoId"

  attribute {
    name = "ordemServicoId"
    type = "S"
  }

  attribute {
    name = "etapa"
    type = "S"
  }

  attribute {
    name = "entrouNaEtapaEm"
    type = "S"
  }

  global_secondary_index {
    name            = "fila-por-etapa"
    hash_key        = "etapa"
    range_key       = "entrouNaEtapaEm"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = local.cfg.point_in_time_recovery
  }
}

# Controle da mensageria: idempotencia (MSG#<messageId>), outbox (OUTBOX#<messageId>) e a concessao
# do publicador (LOCK#outbox). O GSI e esparso: so os itens com o atributo "pendente" (mensagens ainda
# nao publicadas) entram nele. Tudo expira sozinho (TTL em expiraEm, 7 dias).
resource "aws_dynamodb_table" "controle" {
  name         = "${local.prefixo_tabelas}-execution-controle"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "pendente"
    type = "S"
  }

  attribute {
    name = "criadoEm"
    type = "S"
  }

  global_secondary_index {
    name            = "outbox-pendente"
    hash_key        = "pendente"
    range_key       = "criadoEm"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "expiraEm"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = local.cfg.point_in_time_recovery
  }
}
