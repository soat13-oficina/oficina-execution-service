terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Backend parcial: bucket e region vem de backend.hcl (nao versionado).
  #   terraform init -backend-config=backend.hcl
  #
  # Ambientes sao WORKSPACES (hml e prd), como nos repositorios de infraestrutura.
  backend "s3" {
    key          = "oficina/execution-service.tfstate"
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project
      Layer       = "execution-service"
      Environment = local.environment
      ManagedBy   = "terraform"
      Repo        = "oficina-execution-service"
    }
  }
}
