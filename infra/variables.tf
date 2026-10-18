variable "project" {
  description = "Nome do projeto, usado em tags e como prefixo de recursos."
  type        = string
  default     = "oficina"
}

variable "aws_region" {
  description = "Regiao AWS. Precisa ser a mesma da plataforma (oficina-infra-k8s)."
  type        = string
  default     = "us-east-1"
}

# A MESMA bucket do backend deste repositorio. O backend parcial nao expoe seu valor como
# variavel, por isso ele precisa ser informado explicitamente (-var, TF_VAR_state_bucket ou
# terraform.tfvars) para o terraform_remote_state.
variable "state_bucket" {
  description = "Bucket S3 com o state da plataforma, lido aqui via terraform_remote_state."
  type        = string
}

variable "platform_state_key" {
  description = "Key do state da plataforma. Deve casar com o backend de oficina-infra-k8s."
  type        = string
  default     = "oficina/infra-k8s.tfstate"
}

variable "servico" {
  description = "Nome logico do servico; compoe o nome da Role IRSA e do repositorio ECR."
  type        = string
  default     = "oficina-execution-service"
}

variable "service_account" {
  description = "ServiceAccount do Kubernetes que assume a Role via IRSA (k8s/base/serviceaccount.yaml)."
  type        = string
  default     = "oficina-execution-service"
}

# Rode "terraform workspace select hml" (ou prd) antes do plan/apply - o workspace "default" e
# rejeitado de proposito, ver locals.tf.
variable "environments" {
  description = "Configuracao por ambiente, indexada pelo nome do workspace do Terraform."
  type = map(object({
    # Backup continuo (PITR) das tabelas: custa por GB; so em prd.
    point_in_time_recovery      = bool
    retencao_mensagens_segundos = number
    retencao_dlq_segundos       = number
  }))

  default = {
    hml = {
      # Homologacao nao guarda dado que precise ser recuperado.
      point_in_time_recovery      = false
      retencao_mensagens_segundos = 345600 # 4 dias
      retencao_dlq_segundos       = 604800 # 7 dias
    }
    prd = {
      point_in_time_recovery      = true
      retencao_mensagens_segundos = 345600  # 4 dias
      retencao_dlq_segundos       = 1209600 # 14 dias (maximo do SQS)
    }
  }
}
