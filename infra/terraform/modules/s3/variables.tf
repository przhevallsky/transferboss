variable "bucket_name" {
  description = "Name of the S3 bucket"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "enable_versioning" {
  description = "Enable versioning on the S3 bucket"
  type        = bool
  default     = true
}

variable "lifecycle_rules" {
  description = "Custom lifecycle rules for the bucket"
  type = list(object({
    id                     = string
    enabled                = bool
    transition_ia_days     = number
    transition_glacier_days = number
  }))
  default = []
}
