# infra/ — Execution Service

Terraform do que é **só da Execution** (Fase 4, "cada serviço com sua infraestrutura" —
`PLANO-FASE-4.md` do `oficina-app`, seção 9.1). A plataforma (VPC, EKS, OIDC) vem de `oficina-infra-k8s`.

| Recurso | Nome | Para quê |
|---|---|---|
| DynamoDB `pecas` | `oficina-<env>-pecas` | Catálogo e estoque (chave `pecaId`; guarda `quantidadeDisponivel` porque o DynamoDB não faz aritmética em condição — [ADR 0006](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0006-dynamodb-no-execution-service.md)) |
| DynamoDB `reservas` | `oficina-<env>-reservas` | Reservas por OS e peça (`RESERVADA`, `CONSUMIDA`, `LIBERADA`) |
| DynamoDB `execucoes` | `oficina-<env>-execucoes` | Um documento por OS (diagnóstico e histórico aninhados). GSI `fila-por-etapa` (`etapa` + `entrouNaEtapaEm`) é a fila do mecânico |
| DynamoDB `execution-controle` | `oficina-<env>-execution-controle` | Idempotência (`MSG#`), outbox (`OUTBOX#`) e a concessão do publicador (`LOCK#outbox`). GSI esparso `outbox-pendente`; TTL de 7 dias em `expiraEm` |
| Fila SQS FIFO + DLQ | `oficina-<env>-execution-commands.fifo` / `-dlq.fifo` | Comandos da Saga para a Execution. Visibilidade de 60 s, long polling de 20 s, 5 recebimentos antes da DLQ |
| ECR | `oficina-execution-service-<env>` | Imagens do serviço — um por ambiente, para o destroy de `hml` não levar as de `prd` |
| IAM Role (IRSA) | `oficina-<env>-oficina-execution-service` | Pod da Execution: consumir `execution-commands`, mandar para a própria DLQ, responder em `os-saga-replies` (ARN pelo nome) e usar as quatro tabelas. Confia só na ServiceAccount `oficina-execution-service` do namespace `oficina-<env>` |

Todas as tabelas são sob demanda (`PAY_PER_REQUEST`): sem capacidade para dimensionar, custo de centavos
no volume do projeto. Backup contínuo (PITR) só em `prd`.

## Como aplicar

**Só manualmente** — workflow **Infra (Execution)** (`.github/workflows/infra.yml`) → *Run
workflow* → ambiente e ação (`plan`, `apply`, `destroy`). Em PR e push ele só roda `fmt` e
`validate`, sem AWS.

Ordem: `oficina-infra-k8s` → `oficina-infra-database` → `infra/` do OS Service (cria
`os-saga-replies` e o `service-token`) → `infra/` do Billing → **este** → deploy dos serviços →
`oficina-lambda-auth`. Destroy na ordem inversa.

Localmente:

```sh
cd infra
cp backend.hcl.example backend.hcl            # bucket dos states (nao versionado)
cp terraform.tfvars.example terraform.tfvars  # a mesma bucket, para o remote state da plataforma
terraform init -backend-config=backend.hcl
terraform workspace select -or-create hml
terraform plan
```

## Catálogo de demonstração

Os ambientes são recriados a cada subida: não há migração de dados. O `ci-cd.yml` carrega
`scripts/seed-pecas.json` (10 peças, uma sem estoque, ids fixos) depois do deploy **se a tabela de
peças estiver vazia** — um redeploy não devolve o estoque ao inicial. À mão:
`scripts/carregar-seed-pecas.sh <ambiente>` (precisa de AWS CLI e `jq`).

## Contratos com outros repositórios

- **Pipeline deste repo** (`ci-cd.yml`): as tabelas `oficina-<env>-{pecas,reservas,execucoes,execution-controle}`,
  a Role `oficina-<env>-oficina-execution-service`, o ECR `oficina-execution-service-<env>`, além de
  `oficina/<env>/service-token` (OS) e `oficina/<env>/jwt-secret` (Lambda de autenticação).
- **Aplicação** (`PropriedadesDynamo`, `PropriedadesSqs`): monta os nomes a partir de `AMBIENTE` —
  `oficina-<ambiente>-<nome>` (tabelas) e `oficina-<ambiente>-<fila>.fifo` (filas). Mudou o nome aqui,
  muda lá.
- **OS Service** (`oficina-app/infra`): cria `os-saga-replies` e o `service-token`; manda os comandos
  para `execution-commands` pelo nome (a Role dele já pode).
- **Billing** (`oficina-billing-service`): consulta preços em `http://oficina-execution-service:8080`
  (`CATALOGO_PECAS_URL`).
- **Plataforma** (`oficina-infra-k8s`): output `oidc_provider_arn`.
