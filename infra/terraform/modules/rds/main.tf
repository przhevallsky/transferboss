################################################################################
# DB Subnet Group
################################################################################
resource "aws_db_subnet_group" "this" {
  name       = "${var.identifier}-subnet-group"
  subnet_ids = var.subnet_ids

  tags = {
    Name        = "${var.identifier}-subnet-group"
    Environment = var.environment
  }
}

################################################################################
# Security Group
################################################################################
resource "aws_security_group" "this" {
  name_prefix = "${var.identifier}-rds-"
  description = "Security group for RDS PostgreSQL instance"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from allowed security groups"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.allowed_security_groups
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${var.identifier}-rds-sg"
    Environment = var.environment
  }

  lifecycle {
    create_before_destroy = true
  }
}

################################################################################
# Parameter Group (with pgvector support)
################################################################################
resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.identifier}-"
  family      = "postgres16"
  description = "Custom parameter group for ${var.identifier} with pgvector"

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements,pgvector"
  }

  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  tags = {
    Name        = "${var.identifier}-params"
    Environment = var.environment
  }

  lifecycle {
    create_before_destroy = true
  }
}

################################################################################
# RDS Instance
################################################################################
resource "aws_db_instance" "this" {
  identifier = var.identifier

  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.allocated_storage * 2
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  parameter_group_name   = aws_db_parameter_group.this.name

  multi_az                  = var.multi_az
  backup_retention_period   = var.backup_retention_period
  backup_window             = "03:00-04:00"
  maintenance_window        = "Mon:04:00-Mon:05:00"
  deletion_protection       = var.environment == "production"
  skip_final_snapshot       = var.environment != "production"
  final_snapshot_identifier = var.environment == "production" ? "${var.identifier}-final-snapshot" : null

  performance_insights_enabled = true

  tags = {
    Name        = var.identifier
    Environment = var.environment
  }
}
