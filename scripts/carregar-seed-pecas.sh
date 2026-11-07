#!/usr/bin/env bash
# Carrega o catalogo de demonstracao (scripts/seed-pecas.json) na tabela oficina-<ambiente>-pecas.
#
# Uso:   scripts/carregar-seed-pecas.sh <ambiente>          # hml, prd ou local
# Local: AWS_ENDPOINT_URL=http://localhost:4566 scripts/carregar-seed-pecas.sh local
#
# Os ambientes sao recriados a cada subida (nao ha migracao de dados): o pipeline de hml roda este
# script depois do apply do infra/. Reexecutar substitui os itens e volta o estoque ao inicial.
# Precisa de aws CLI e jq.
set -euo pipefail

ambiente="${1:?Informe o ambiente: hml, prd ou local}"
pasta="$(cd "$(dirname "$0")" && pwd)"
tabela="oficina-${ambiente}-pecas"
endpoint=()
if [[ -n "${AWS_ENDPOINT_URL:-}" ]]; then
  endpoint=(--endpoint-url "$AWS_ENDPOINT_URL")
fi

# Mesmos atributos do PecaItem: o disponivel nasce igual ao estoque, nada reservado, versao 1.
requisicao="$(jq --arg tabela "$tabela" '{($tabela): [ .[] | {PutRequest: {Item: {
    pecaId: {S: .pecaId},
    descricao: {S: .descricao},
    marca: {S: .marca},
    preco: {N: (.preco | tostring)},
    quantidadeEstoque: {N: (.quantidadeEstoque | tostring)},
    quantidadeReservada: {N: "0"},
    quantidadeDisponivel: {N: (.quantidadeEstoque | tostring)},
    codigoReferencia: {S: .codigoReferencia},
    categoria: {S: .categoria},
    versao: {N: "1"}
}}} ]}' "$pasta/seed-pecas.json")"

# batch-write-item aceita ate 25 itens por chamada; o seed cabe numa.
aws "${endpoint[@]}" dynamodb batch-write-item --request-items "$requisicao" >/dev/null
echo "Seed carregado em ${tabela}: $(jq length "$pasta/seed-pecas.json") pecas."
