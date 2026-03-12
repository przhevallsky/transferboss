rootProject.name = "transferboss"

include(
    "services:transfer-service",
    "services:outbox-service",
    "services:pricing-service",
    "services:mock-payment-service",
    "services:mock-payout-service",
    "services:analytics-etl",
    "services:llm-service",
    "services:mongodb-migration",
)
