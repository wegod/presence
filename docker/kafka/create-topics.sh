#!/usr/bin/env bash
# Create Kafka topics required by the seckill MVP (apache/kafka image).
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
TOPIC="${SECKILL_STOCK_TOPIC:-seckill.stock.deduct}"
PARTITIONS="${SECKILL_STOCK_PARTITIONS:-3}"
REPLICATION="${SECKILL_STOCK_REPLICATION:-1}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-/opt/kafka/bin/kafka-topics.sh}"

echo "Waiting for Kafka at ${BOOTSTRAP}..."
for i in $(seq 1 60); do
  if "${KAFKA_TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; then
    break
  fi
  if [[ "$i" -eq 60 ]]; then
    echo "Kafka did not become ready in time" >&2
    exit 1
  fi
  sleep 2
done

echo "Creating topic ${TOPIC} (partitions=${PARTITIONS}, rf=${REPLICATION})..."
"${KAFKA_TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP}" \
  --create \
  --if-not-exists \
  --topic "${TOPIC}" \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION}"

echo "Topics:"
"${KAFKA_TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP}" --list
echo "Kafka topic init done."
