#!/bin/bash
# TransferHub: Kafka topics initialization
# This script runs once after Kafka is healthy

set -e

BOOTSTRAP_SERVER="transferhub-kafka:29092"

echo "============================================"
echo "Creating Kafka topics for TransferHub..."
echo "============================================"

# Function to create topic with error handling
create_topic() {
    local topic=$1
    local partitions=$2
    local retention_ms=$3  # retention in milliseconds

    echo "Creating topic: $topic (partitions=$partitions, retention=${retention_ms}ms)"
    kafka-topics --bootstrap-server $BOOTSTRAP_SERVER \
        --create \
        --if-not-exists \
        --topic "$topic" \
        --partitions "$partitions" \
        --replication-factor 1 \
        --config retention.ms="$retention_ms"
}

# =============================================
# Topics produced by Transfer Service (via Outbox)
# Key: transfer_id — все события одного перевода в одной партиции (ordering guarantee)
# =============================================

SEVEN_DAYS_MS=604800000

create_topic "transfer.events"                          6   $SEVEN_DAYS_MS
create_topic "transfers.transfer.created"              12  $SEVEN_DAYS_MS
create_topic "transfers.transfer.status_changed"       12  $SEVEN_DAYS_MS
create_topic "transfers.payment.requested"             12  $SEVEN_DAYS_MS
create_topic "transfers.compliance.requested"          6   $SEVEN_DAYS_MS
create_topic "transfers.payout.requested"              12  $SEVEN_DAYS_MS
create_topic "transfers.payment.refund.requested"      6   $SEVEN_DAYS_MS

# =============================================
# Topics consumed by Transfer Service
# Produced by other teams' services (Payments, Payouts, Identity)
# We create them here for local development; in production, owning teams create their topics
# =============================================

create_topic "payments.payment.captured"               6   $SEVEN_DAYS_MS
create_topic "payments.payment.failed"                 6   $SEVEN_DAYS_MS
create_topic "payments.payment.refunded"               6   $SEVEN_DAYS_MS
create_topic "payouts.payout.initiated"                6   $SEVEN_DAYS_MS
create_topic "payouts.payout.completed"                6   $SEVEN_DAYS_MS
create_topic "payouts.payout.failed"                   6   $SEVEN_DAYS_MS
create_topic "identity.user.blocked"                   3   $SEVEN_DAYS_MS

# =============================================
# Retry and Dead Letter Topics
# =============================================

create_topic "transfers.notification.retry"            6   $SEVEN_DAYS_MS
create_topic "transfers.notification.redirect"         3   $SEVEN_DAYS_MS
create_topic "transfers.payment-consumer.dlt"          3   $SEVEN_DAYS_MS
create_topic "transfers.payout-consumer.dlt"           3   $SEVEN_DAYS_MS

echo ""
echo "============================================"
echo "All topics created successfully!"
echo "============================================"

# List all topics for verification
echo ""
echo "Current topics:"
kafka-topics --bootstrap-server $BOOTSTRAP_SERVER --list
