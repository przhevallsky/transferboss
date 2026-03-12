# Troubleshooting Workflow: From Alert to Root Cause

## Three Pillars — Cross-Linked in Grafana

Grafana datasources are pre-configured with cross-linking:
- **Prometheus** → exemplars link to **Tempo** traces
- **Loki** → derived field `traceId` links to **Tempo** traces
- **Tempo** → `tracesToLogsV2` links to **Loki** logs (filtered by traceId)

## Workflow: Alert → Root Cause in 3 Clicks

### 1. Metrics → Trace (Exemplars)
1. Open **Transfer Service RED** dashboard in Grafana
2. On any histogram panel (e.g., Latency p95), enable **Show Exemplars**
3. Click on an exemplar data point → opens the corresponding trace in Tempo

### 2. Logs → Trace
1. Open **Loki Explorer** in Grafana
2. Query: `{service="transfer-service"} |= "ERROR"`
3. Find a log line with `traceId` field
4. Click the **Tempo** link next to the traceId → opens the full distributed trace

### 3. Trace → Logs
1. Open a trace in **Tempo**
2. Click **View Logs** on any span
3. Grafana navigates to Loki with `{traceId="<id>"}` filter pre-applied

## Example: Diagnosing a Slow Transfer

1. Alert fires: `HighLatency` (p99 > 500ms)
2. Open Transfer Service RED dashboard → Latency panel → see spike
3. Click exemplar on the spike → Tempo shows trace with spans:
   - `POST /api/v1/transfers` (280ms)
   - `gRPC PricingService/ValidateQuote` (210ms) ← bottleneck
   - `PostgreSQL INSERT` (15ms)
   - `Kafka produce` (8ms)
4. Root cause: Pricing Service gRPC call is slow
5. Click span → View Logs → see pricing service error details

## Access URLs (Local Development)

| Tool | URL | Purpose |
|------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards, explore |
| Prometheus | http://localhost:9091 | Raw metrics, targets |
| Tempo | http://localhost:3200 | Trace search API |
| Loki | http://localhost:3100 | Log aggregation API |
| Alertmanager | http://localhost:9093 | Alert routing |

## Starting the Monitoring Stack

```bash
docker compose --profile monitoring up -d
```
