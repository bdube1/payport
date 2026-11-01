// Payport load and stress tests (k6, https://k6.io). Run from a machine OUTSIDE the servers being tested, against
// staging/pilot only. Payments use a TEST (sk_test_) merchant key, so they are simulated: no real money moves.
//
//   k6 run -e PROFILE=smoke  -e API=https://api-staging.payport.africa -e STORE=https://store-staging.payport.africa \
//          -e TEST_KEY=sk_test_... -e STORE_SLUG=some-store loadtest/payport.js
//   PROFILE = smoke (1 user, 1 min) | load (ramp to 200 users, 15 min) | stress (ramp to 1000, find the limit)
//           | spike (0 -> 800 users in 30 s) | soak (150 users for 2 hours)
//
// Pass/fail thresholds: under 1% errors; 95% of answers in under 800 ms (catalogue and store pages) and
// 1.5 s (creating a checkout). k6 exits non-zero when a threshold fails.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const API = __ENV.API || 'https://api-staging.payport.africa';
const STORE = __ENV.STORE || 'https://store-staging.payport.africa';
const KEY = __ENV.TEST_KEY || '';
const SLUG = __ENV.STORE_SLUG || '';
const PROFILE = __ENV.PROFILE || 'smoke';

if (KEY && !KEY.startsWith('sk_test_')) throw new Error('Use a TEST merchant key (sk_test_...): load tests must never move real money');

const errors = new Rate('payport_errors');
const checkoutTime = new Trend('payport_checkout_create_ms', true);

const PROFILES = {
  smoke: { vus: 1, duration: '1m' },
  load: { stages: [{ duration: '3m', target: 50 }, { duration: '5m', target: 200 }, { duration: '5m', target: 200 }, { duration: '2m', target: 0 }] },
  stress: { stages: [{ duration: '2m', target: 200 }, { duration: '3m', target: 400 }, { duration: '3m', target: 700 }, { duration: '3m', target: 1000 }, { duration: '3m', target: 0 }] },
  spike: { stages: [{ duration: '30s', target: 800 }, { duration: '1m', target: 800 }, { duration: '30s', target: 0 }] },
  soak: { stages: [{ duration: '5m', target: 150 }, { duration: '2h', target: 150 }, { duration: '5m', target: 0 }] },
};

export const options = Object.assign({}, PROFILES[PROFILE] || PROFILES.smoke, {
  thresholds: {
    payport_errors: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<800'],
    'http_req_duration{kind:store}': ['p(95)<800'],
    payport_checkout_create_ms: ['p(95)<1500'],
  },
  noConnectionReuse: false,
  userAgent: 'payport-loadtest/1.0',
});

function ok(res, what) {
  const good = check(res, { [`${what} answered`]: r => r.status >= 200 && r.status < 400 });
  errors.add(!good);
  return good;
}

export default function () {
  group('public reads (app, website, USSD and WhatsApp use these)', () => {
    ok(http.get(`${API}/actuator/health`, { tags: { kind: 'read' } }), 'health');
    ok(http.get(`${API}/api/v1/products`, { tags: { kind: 'read' } }), 'products');
    ok(http.get(`${API}/api/v1/billers`, { tags: { kind: 'read' } }), 'billers');
    ok(http.get(`${API}/api/v1/markets`, { tags: { kind: 'read' } }), 'markets');
  });

  group('Payport Store', () => {
    ok(http.get(`${STORE}/`, { tags: { kind: 'store' } }), 'store front page');
    if (SLUG) ok(http.get(`${STORE}/s/${SLUG}`, { tags: { kind: 'store' } }), 'a store');
    ok(http.get(`${STORE}/?q=shoes`, { tags: { kind: 'store' } }), 'store search');
  });

  if (KEY) group('merchant API: test checkouts (simulated, no money)', () => {
    const ref = `lt-${__VU}-${__ITER}-${Date.now()}`;
    const res = http.post(`${API}/api/v1/merchant/checkout-sessions`, JSON.stringify({
      amountCents: 100 + (__ITER % 900), currency: 'USD', reference: ref, description: 'Load test',
    }), { headers: { Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' }, tags: { kind: 'write' } });
    checkoutTime.add(res.timings.duration);
    if (ok(res, 'create checkout')) {
      const id = res.json('data.id');
      if (id) ok(http.get(`${API}/api/v1/merchant/checkout-sessions/${id}`, { headers: { Authorization: `Bearer ${KEY}` }, tags: { kind: 'read' } }), 'read checkout');
    }
  });

  sleep(1 + Math.random() * 2);   // people pause between pages
}
