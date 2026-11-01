# Running Payport at scale on DigitalOcean

## Shape

```
                 DigitalOcean Load Balancer (https, health check /actuator/health/readiness)
                     │                       │
          ┌──────────┴─────────┐   ┌─────────┴──────────┐
          │ app droplet 1      │   │ app droplet 2 (+n) │   gateway (8085) + store (8086)
          │ SCHEDULING=true    │   │ SCHEDULING=false   │   same build, same .env
          └──────────┬─────────┘   └─────────┬──────────┘
                     └──────────┬────────────┘
             DigitalOcean Managed MySQL 8 (primary + standby, daily backups, point-in-time restore)
                     │
             monitoring droplet: Grafana, Prometheus, Loki, Elasticsearch, Kibana (scrapes the app droplets
             on their private addresses with the metrics token)
```

All droplets in one VPC; only the load balancer and the monitoring dashboard have public addresses. The portals
(static files) can stay on the app droplets behind Caddy or move to DigitalOcean Spaces + CDN.

## What makes several app servers safe

- **One scheduler.** Payment windows, fulfilment, reconciliation, settlement, screening, pushes and the store's
  event reader run on one server only: set `PAYPORT_SCHEDULING_ENABLED=false` and `COMMERCE_SCHEDULING_ENABLED=false`
  on every app droplet except one. If that droplet is lost, set them to `true` on another and restart it.
- **No sessions on the server.** Sign-in is by signed tokens, so any server can answer any request; no sticky
  sessions are needed.
- **Settings reach every server** within 10 to 60 seconds (short caches), without restarts.
- **Money is protected by the database**, not by the server: the ledger rules, one-use tokens and "claim" updates
  are atomic in MySQL, so two servers cannot pay or deliver the same thing twice.
- Rate limits are counted per server; with n servers a caller gets at most n times the limit (acceptable; the
  account lockouts are in the database and shared).

## Sizes for the pilot (start, then scale on the dashboards)

| Piece | Start | Scale when |
|---|---|---|
| App droplets | 2 x 4 vCPU / 8 GB (General Purpose) | CPU > 60% for 15 min, or p95 > 800 ms: add a droplet |
| Managed MySQL | 4 vCPU / 8 GB, standby node on | connections > 70% of max, or slow queries grow: next size, add a read replica for reports |
| Monitoring | 2 vCPU / 4 GB | Elasticsearch disk > 70% |
| Load balancer | Small, 2 nodes | DigitalOcean scales it |

JVM: `-Xms3g -Xmx3g -XX:+UseG1GC` on 8 GB droplets. Database pool: `spring.datasource.hikari.maximum-pool-size`
x number of servers must stay under the database's connection limit.

## Steps

1. Create the VPC, the managed MySQL cluster (restore the staging dump into it), and two app droplets from the same
   image (Java 21, Caddy, the build).
2. Same `.env` on both, except `PAYPORT_SCHEDULING_ENABLED` / `COMMERCE_SCHEDULING_ENABLED` (true on one only), and
   `FORWARD_HEADERS_STRATEGY=native` so the real client address comes through the load balancer.
3. Load balancer: forwarding https 443 -> http 8085 (api) and 8086 (store); health check `/actuator/health/readiness`;
   enable "PROXY protocol" off, "X-Forwarded-For" on.
4. Point `api`, `store`, `app`, `admin`, `merchant` DNS at the load balancer.
5. Run the load tests in `loadtest/` from a separate droplet: smoke, load, stress, spike, soak.
6. Firewall: app droplets accept 8085/8086 only from the load balancer and the monitoring droplet; MySQL only from
   the app droplets; SSH only from your office address with keys (no passwords).
