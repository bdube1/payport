# Load and stress testing

`payport.js` is a [k6](https://k6.io) script. Run it from a separate machine (for example a small DigitalOcean
droplet in the same region), never from the servers being tested, and only against staging/pilot.

Payments use a **test** merchant key (`sk_test_...`): checkouts are simulated, no money moves. The script refuses a
live key.

```
k6 run -e PROFILE=smoke  -e API=https://api-staging.payport.africa -e STORE=https://store-staging.payport.africa -e TEST_KEY=sk_test_... -e STORE_SLUG=<a store> loadtest/payport.js
k6 run -e PROFILE=load   ...    # ramps to 200 users for 15 minutes
k6 run -e PROFILE=stress ...    # ramps to 1000 users: where does it break?
k6 run -e PROFILE=spike  ...    # 0 to 800 users in 30 seconds
k6 run -e PROFILE=soak   ...    # 150 users for 2 hours (memory leaks, connection pools)
```

Pass when: errors under 1%, 95% of reads under 800 ms, 95% of checkout creations under 1.5 s.

While it runs, watch the TV dashboards (Infrastructure and Payment processing): CPU, memory, database connections
and slow queries. Rate limiting answers 429 to one address sending too much; from one test machine raise
`RATE_LIMIT_*` on staging for the test, or run from several machines.

Order of work: smoke -> load -> stress (note the user count where errors or p95 cross the limits) -> fix or scale
-> spike -> soak. Keep each run's summary (`--summary-export=result.json`).
