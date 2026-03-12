# ADR-010: Terraform for Infrastructure as Code

**Status:** Accepted
**Date:** 2025-12-01

## Decision

Terraform with modular structure (VPC, EKS, RDS, S3) and S3+DynamoDB backend.

## Rationale

- **Declarative:** Desired state in `.tf` files. `terraform plan` shows diff before apply.
- **Modular:** Reusable modules for VPC, EKS, RDS, S3. Environment-specific configs (dev, production).
- **State management:** S3 backend with DynamoDB locking prevents concurrent modifications.
- **Drift detection:** `terraform plan` detects manual AWS Console changes.
- **Multi-cloud capable:** Not locked to AWS CDK/CloudFormation.

## Consequences

- State file contains sensitive data (encrypted in S3, restricted IAM access)
- Learning curve for team members unfamiliar with HCL
- All infrastructure changes must go through Terraform (no manual Console changes in production)
