# Contratos copiados

Cópia de `docs/contratos/schemas/` do **oficina-app** (OS Service), fonte da verdade dos contratos da
Saga ([ADR 0005](https://github.com/soat13-oficina/oficina-app/blob/desenvolvimento/docs/adr/0005-sqs-fifo-outbox-e-idempotencia.md)).
Usada só nos testes de contrato (`ContratoDasMensagensTest`): as mensagens que o Billing produz
precisam passar no schema do seu `type`, e os exemplos dos comandos que ele consome precisam ser
processados sem erro.

Mudou um contrato no oficina-app → copiar a pasta de novo aqui no mesmo PR que adapta o Billing.
