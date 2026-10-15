# oficina-execution-service

Execution Service da oficina mecânica — **fila, diagnóstico, execução e estoque de peças**.
Tech Challenge SOAT 13, Fase 4: um dos três microsserviços que substituem o monólito
`oficina-app`.

> **Status:** esqueleto. Segurança (JWT), observabilidade, health checks, Swagger, gate de
> cobertura, manifestos Kubernetes e pipeline de CI. As regras de negócio chegam no Passo 4
> da Fase 4, extraídas dos módulos `pecainsumo` e de diagnóstico/execução do `oficina-app`.

## Responsabilidade

| Faz | Não faz |
|---|---|
| Fila de diagnóstico e de execução, em ordem de chegada | Não abre nem altera ordens de serviço — dono: OS Service |
| Diagnóstico (serviços com mão de obra e peças previstas), início, conclusão e aborto da execução | Não calcula orçamento nem cobra — dono: Billing Service |
| Catálogo e estoque de peças; reserva, consumo e liberação atômicos por OS | Não acessa o banco de nenhum outro serviço |
| Consulta interna de preços para o Billing (`POST /internal/pecas-insumos/consulta`) | |
| Publica os eventos que movem a Saga (`DiagnosticoConcluido`, `PecasReservadas`, `ExecucaoConcluida`…) | |

Banco: **DynamoDB** (o NoSQL exigido pela Fase 4), a partir do Passo 4.

## Arquitetura e decisões

As decisões da Fase 4 vivem no repositório que coordena a Saga (`oficina-app`):

- [ADR 0003 — divisão em microsserviços e dono dos dados](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0003-divisao-em-microsservicos-e-dono-dos-dados.md)
- [ADR 0004 — Saga orquestrada](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0004-saga-orquestrada.md)
- [ADR 0005 — SQS FIFO, outbox e idempotência](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0005-sqs-fifo-outbox-e-idempotencia.md)
- [ADR 0006 — DynamoDB no Execution Service](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0006-dynamodb-no-execution-service.md)
- [ADR 0008 — exposição por ALB](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0008-exposicao-e-roteamento.md)
- [Contratos das mensagens](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/contratos/mensagens.md) — este serviço consome `execution-commands` e publica em `os-saga-replies`
- [Convenções de nomes](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/arquitetura/convencoes-de-nomes.md)

## Stack

Java 21 · Spring Boot 4.0.5 (WebMVC, Security, Validation, Actuator) · jjwt 0.12 ·
springdoc-openapi 3 · Micrometer Prometheus · JaCoCo (gate de 80%) · SonarCloud ·
Kustomize · GitHub Actions. A partir do Passo 4: AWS SDK v2 (DynamoDB Enhanced Client, SQS).

## Segurança

Valida o JWT com o mesmo segredo do OS Service e da Lambda (`JWT_SECRET`,
`oficina/<env>/jwt-secret`). Não emite token e não tem tabela de usuários:

| Token | Origem | Papel |
|---|---|---|
| `tipo = CLIENTE` | Lambda `token` (login por CPF) | `ROLE_CLIENTE` |
| `tipo = FUNCIONARIO` ou sem `tipo` | OS Service (`POST /api/auth/login`) | `ROLE_FUNCIONARIO` (+ `roles`, se houver) |
| outro `tipo`, expirado, assinatura inválida | — | anônimo → `401` |

Diagnóstico, execução e estoque exigirão `ROLE_FUNCIONARIO` (Passo 4). Públicos:
`/actuator/health/**`, `/actuator/prometheus` (em nuvem só na porta 8081, fora do Ingress) e o
Swagger.

## Rodando localmente

```sh
./mvnw spring-boot:run
```

- API: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

Sem variáveis, usa o segredo JWT de desenvolvimento (o mesmo default do `oficina-app`), então
um token emitido pelo OS rodando localmente é aceito aqui.

## Testes e cobertura

```sh
./mvnw clean verify
```

Roda os testes e o **gate do JaCoCo: mínimo de 80% de linhas e de branches no serviço
inteiro** — o build falha abaixo disso. Relatório em `target/site/jacoco/index.html` e como
artefato `jacoco-report` no CI. A análise do SonarCloud (job `qualidade`) usa o mesmo
relatório.

## CI/CD

| Job | Quando | O que faz |
|---|---|---|
| `testes` | PR e push | Build, testes e gate de cobertura |
| `qualidade` | PR e push | SonarCloud com quality gate bloqueante (exige o secret `SONAR_TOKEN`) |
| `validacao` | PR e push | `kubectl kustomize` dos overlays `hml` e `prd` |
| `guard` | PR para `homologacao`/`master` | Fluxo `feature/*` → `desenvolvimento` → `homologacao` → `master` |
| `build`, `deploy` | *(Passo 2/4)* | Imagem no ECR e deploy no EKS, junto com o `infra/` do serviço |

Status checks obrigatórios em `master` e `homologacao`: `testes`, `qualidade`, `validacao`, `guard`.

## Kubernetes

`k8s/base` (ServiceAccount com IRSA, Deployment, Service `ClusterIP`, HPA, Ingress do ALB) e
`k8s/overlays/{hml,prd}`. O namespace (`oficina-hml`/`oficina-prd`) é compartilhado pelos três
serviços e não é criado aqui. Dentro do cluster: `http://oficina-execution-service:8080`.
