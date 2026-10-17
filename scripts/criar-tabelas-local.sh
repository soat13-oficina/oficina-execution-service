#!/usr/bin/env bash
# Cria no LocalStack as tabelas do Execution Service (ADR 0006), como o Terraform do infra/ as cria
# na AWS. So para desenvolvimento local - em nuvem a aplicacao nunca cria tabela.
#
# Uso: scripts/criar-tabelas-local.sh [ambiente]      # padrao: local
#      AWS_ENDPOINT_URL=http://localhost:4566 (padrao) | AWS_REGION=us-east-1 (padrao)
#
# Mudou o desenho de uma tabela: muda aqui, em src/test/.../TabelasDeTeste.java e no Terraform.
set -euo pipefail

ambiente="${1:-local}"
endpoint="${AWS_ENDPOINT_URL:-http://localhost:4566}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
dynamo=(aws --endpoint-url "$endpoint" dynamodb)

criar() {
  local nome="$1"; shift
  if "${dynamo[@]}" describe-table --table-name "$nome" >/dev/null 2>&1; then
    echo "ja existe: $nome"
    return
  fi
  "${dynamo[@]}" create-table --table-name "$nome" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
  echo "criada: $nome"
}

criar "oficina-${ambiente}-pecas" \
  --attribute-definitions AttributeName=pecaId,AttributeType=S \
  --key-schema AttributeName=pecaId,KeyType=HASH

criar "oficina-${ambiente}-reservas" \
  --attribute-definitions AttributeName=ordemServicoId,AttributeType=S AttributeName=pecaId,AttributeType=S \
  --key-schema AttributeName=ordemServicoId,KeyType=HASH AttributeName=pecaId,KeyType=RANGE

# Idempotencia (MSG#) e outbox (OUTBOX#). O GSI esparso so enxerga os itens com o atributo "pendente".
criar "oficina-${ambiente}-execution-controle" \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=pendente,AttributeType=S AttributeName=criadoEm,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH \
  --global-secondary-indexes 'IndexName=outbox-pendente,KeySchema=[{AttributeName=pendente,KeyType=HASH},{AttributeName=criadoEm,KeyType=RANGE}],Projection={ProjectionType=ALL}'

"${dynamo[@]}" update-time-to-live --table-name "oficina-${ambiente}-execution-controle" \
  --time-to-live-specification Enabled=true,AttributeName=expiraEm >/dev/null
echo "TTL (expiraEm) ligado em oficina-${ambiente}-execution-controle"
