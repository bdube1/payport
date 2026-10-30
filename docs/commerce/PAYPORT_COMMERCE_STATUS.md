# PayPort Commerce: status

**Current phase:** 0 (assessment) delivered on 30 October 2026, waiting for review.

## Verified
- PSP stack and capabilities read from the code (see PHASE0_ASSESSMENT.md, sections 1 and 2).
- PSP test suite green in CI on main (364 tests; 258 in the main job, 106 ledger tests in the real-MySQL job).

## In progress
- Nothing. Phase 1 starts after approval.

## Defects found in the PSP (not changed; need approval)
- Card details reveal passes PAN and CVV through PayPort servers (PCI scope).
- Website assistant sends visitor messages to a free-tier external model.

## Risks
- PCI scope of the consumer site (copy-and-pay widget) to be confirmed with the QSA.
- Staging server memory is too small for Commerce plus monitoring.

## Test results
- Phase 0 has no Commerce code and therefore no Commerce tests.

## Next steps
- Your decisions 1 to 6 (PHASE0_ASSESSMENT.md, end), then the Phase 1 specification.
