locals {
  environment = terraform.workspace

  # Falha cedo e com mensagem legivel quando alguem esquece de selecionar o workspace - sem isto,
  # um apply no workspace default criaria um terceiro conjunto de tabelas fora de qualquer ambiente.
  cfg = try(
    var.environments[local.environment],
    file("ERRO: workspace '${terraform.workspace}' invalido. Rode 'terraform workspace select hml' ou 'terraform workspace select prd' antes do plan/apply.")
  )

  namespace = "${var.project}-${local.environment}"

  # Nome fisico das tabelas: oficina-<ambiente>-<nome> (ADR 0006). A aplicacao os monta a partir de
  # dynamodb.ambiente (PropriedadesDynamo#tabela): mudou aqui, muda la.
  prefixo_tabelas = "${var.project}-${local.environment}"

  # Nome fisico das filas: oficina-<ambiente>-<fila>.fifo (ADR 0005), descoberto pela aplicacao
  # com GetQueueUrl a partir de mensageria.sqs.ambiente.
  prefixo_filas = "${var.project}-${local.environment}"

  # Emissor OIDC do cluster sem "https://", como as condicoes da trust policy exigem.
  oidc_provider = element(split("oidc-provider/", local.platform.oidc_provider_arn), 1)
}
