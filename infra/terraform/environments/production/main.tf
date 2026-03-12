provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
    }
  }
}

################################################################################
# VPC
################################################################################
module "vpc" {
  source = "../../modules/vpc"

  vpc_cidr             = "10.0.0.0/16"
  environment          = var.environment
  project_name         = var.project_name
  availability_zones   = ["${var.aws_region}a", "${var.aws_region}b", "${var.aws_region}c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
}

################################################################################
# EKS
################################################################################
module "eks" {
  source = "../../modules/eks"

  cluster_name        = "${var.project_name}-${var.environment}"
  cluster_version     = "1.29"
  vpc_id              = module.vpc.vpc_id
  subnet_ids          = module.vpc.private_subnet_ids
  node_instance_types = ["m5.xlarge"]
  node_desired_size   = 3
  node_min_size       = 2
  node_max_size       = 10
  environment         = var.environment
}

################################################################################
# RDS
################################################################################
module "rds" {
  source = "../../modules/rds"

  identifier              = "${var.project_name}-${var.environment}"
  engine_version          = "16.3"
  instance_class          = "db.r6g.xlarge"
  allocated_storage       = 100
  db_name                 = "transferhub"
  db_username             = "transferhub"
  vpc_id                  = module.vpc.vpc_id
  subnet_ids              = module.vpc.private_subnet_ids
  allowed_security_groups = []
  environment             = var.environment
  multi_az                = true
  backup_retention_period = 30
}

################################################################################
# S3
################################################################################
module "s3" {
  source = "../../modules/s3"

  bucket_name       = "${var.project_name}-${var.environment}-data"
  environment       = var.environment
  enable_versioning = true
}
