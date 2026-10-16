// PayPort staging load test (k6, https://k6.io). Never point this at production.
//
//   k6 run -e BASE=https://staging-api.payport.africa -e CUSTOMER_TOKEN=... scripts/phase9/k6-staging.js
//
// Scenarios (open-model arrival rates, so a slow server cannot slow the test down and hide latency):
//   browse    public catalogue reads                      20 req/s for 5 min
//   initiate  guest payment intents (initiate only)       5 req/s for 5 min  (no provider is called)
//   wallet    signed-in wallet reads (needs CUSTOMER_TOKEN) 10 req/s for 5 min
// Thresholds mirror docs/phase9/SLO.md; k6 exits non-zero when one is breached.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOKEN = __ENV.CUSTOMER_TOKEN || '';

export const options = {
  scenarios: {
    browse:   { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '5m', preAllocatedVUs: 40, exec: 'browse' },
    initiate: { executor: 'constant-arrival-rate', rate: 5,  timeUnit: '1s', duration: '5m', preAllocatedVUs: 20, exec: 'initiate' },
    ...(TOKEN ? { wallet: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '5m', preAllocatedVUs: 20, exec: 'wallet' } } : {}),
  },
  thresholds: {
    'http_req_failed{scenario:browse}':   ['rate<0.001'],
    'http_req_duration{scenario:browse}': ['p(95)<300', 'p(99)<800'],
    'http_req_failed{scenario:initiate}': ['rate<0.001'],
    'http_req_duration{scenario:initiate}': ['p(95)<500', 'p(99)<1500'],
    'http_req_duration{scenario:wallet}': ['p(95)<300'],
  },
};

export function browse() {
  const r = http.get(`${BASE}/api/v1/products`);
  check(r, { 'catalogue 200': (x) => x.status === 200 });
}

function ref() {
  const d = new Date().toISOString().slice(0, 10).replace(/-/g, '');
  return `PP-LOAD-${d}-${String(Math.floor(Math.random() * 1e6)).padStart(6, '0')}`;
}

export function initiate() {
  const body = JSON.stringify({ reference: ref(), productTypeCode: 'AIRTIME_ECONET', recipientIdentifier: '0771234567',
    amountCents: 100, currency: 'USD', customerPhone: '+263771234567', channel: 'WEB' });
  const r = http.post(`${BASE}/api/v1/transactions/initiate`, body, { headers: { 'Content-Type': 'application/json' } });
  check(r, { 'initiate 200 (or 409 duplicate ref)': (x) => x.status === 200 || x.status === 409 });
}

export function wallet() {
  const r = http.get(`${BASE}/api/v1/wallet`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  check(r, { 'wallet 200': (x) => x.status === 200 });
}
