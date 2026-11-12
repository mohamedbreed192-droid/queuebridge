# Executed verification

Executed on 13 November 2026 (Asia/Dubai), with Codex assistance. These are observed results, not a claim of production readiness.

## Automated Java verification — PASS

`./mvnw --batch-mode verify` completed **BUILD SUCCESS**, with **16 tests, 0 failures, 0 errors, 0 skipped**. The final run took 28.059 seconds; the test class took 23.41 seconds. Java 21, Spring Boot 3.5.16, real PostgreSQL 17.12 and RabbitMQ 4.1 containers, and Docker 29.9.2 were used. Maven 3.9.11 was downloaded by the checked-in wrapper and its pinned SHA-256 was verified.

| Test | Evidence exercised |
|---|---|
| validationAndDecimals | Bean Validation and exact AED 149.70 calculation; unknown product rejection |
| idempotencyAndConflict | Matching request returns the same UUID; changed content conflicts |
| concurrentIdempotency | Sixteen submissions on eight threads produce one order and one outbox row |
| atomicOrderOutbox | Database constraint forces outbox insert failure; order insert rolls back |
| confirmedPublishAndDuplicateOutcome | Real broker publish confirmation; duplicate delivery creates one inbox/business outcome |
| unroutablePublishRetained | Remove real queue binding; mandatory return leaves outbox unpublished and retryable |
| rollbackThenBrokerRedelivery | Force consumer DB failure; outcome/inbox roll back; broker delivery succeeds after fault removal |
| boundedRetriesAndDeadLetters | Three processing failures; terminal FAILED; real message reaches DLQ |
| transientBackoffAndBusinessRejection | Retry row has future availability; transient succeeds; business decision rejects |
| malformedMessageDeadLetter | Real invalid broker payload reaches DLQ |
| replayAuthorizationCsrfAndBounds | Operator denied; missing CSRF denied; admin replay audited; third replay denied; disabled lab denied |
| apiValidationAndBrowserRendering | Invalid authenticated JSON rejected; Thymeleaf overview/detail render |
| overlappingPublishersDoNotShareRows | Two publisher threads produce one send/committed attempt for one outbox row |
| authorizedReplayRecoversThroughBroker | Replay gets new event ID; worker fulfills; old inbox/audit records remain |
| oversizedJsonRejected | Body beyond 16 KiB rejected with HTTP 413 |
| actualPasswordLogin | Real configured BCrypt login succeeds; wrong password fails |

Retry tests advance database eligibility to avoid sleeping through backoff; the browser test below also waited through the real retry schedule. Tests do not force-kill the JVM at every crash window or simulate a multi-node broker partition. Those windows are described in the architecture, not claimed as exhaustively tested.

The managed environment required a separate full JDK 21, its supplied proxy CA trust store, workspace-local Maven cache, and explicit Maven proxy settings. The preinstalled Java compiler could not target release 21. No trust checks were disabled. Initial compile/configuration attempts failed and were corrected. The first browser run exposed a real BCrypt encoder configuration defect; it was fixed and the actual-password regression test was added. The initial Docker image could not read a mode-600 jar as its non-root user; explicit read permissions fixed it. Temporary disk exhaustion during image/container creation was resolved by clearing disposable verification artifacts. Final verification above passed after those fixes.

## Compose and real browser — PASS

Built and started the actual Compose web/worker/PostgreSQL/RabbitMQ stack. Playwright 1.62.1 drove installed Chromium 156.0.8078.79. All **14 browser checks** passed; see [machine-readable results](browser-results.json):

- Operator submission and asynchronous fulfillment.
- Same-key identity and changed-content HTTP 409 with an explanation.
- Operator recovery controls hidden.
- Duplicate event delivered and DUPLICATE observation shown.
- Transient failure retried and fulfilled.
- Permanent business rejection shown.
- Three failed attempts reached FAILED with real 5/15 second delayed retries.
- Administrator replay fulfilled with preserved actor/reason/history.
- Stopping RabbitMQ left an ACCEPTED order and pending outbox.
- Starting RabbitMQ drained the pending outbox and fulfilled the order.
- Failed-order search reflected recovery.
- Mobile page fit a 390px viewport with no page overflow.
- No browser JavaScript errors.

[Desktop screenshot](screenshots/desktop.png) (1440px viewport), [mobile screenshot](screenshots/mobile.png) (390px viewport), and [recovery history](screenshots/recovery.png) are unmodified captures of database-backed pages, not design mockups. The six synthetic orders ended as five fulfilled and one rejected. A broker CLI observation after the run showed the work queue at 0 ready / 0 unacknowledged and the dead-letter queue at 1 ready / 0 unacknowledged, preserving the exhausted original after replay.

Reproduce the optional browser check after starting Compose, with Node.js, installed Chromium, and a local `.env`:

```sh
npm install --prefix ../queuebridge-browser-tools playwright@1.62.1
NODE_PATH=../queuebridge-browser-tools/node_modules node scripts/browser-smoke.cjs
```

Set `CHROMIUM_PATH` if Chromium is not `/usr/bin/chromium`. This script creates synthetic orders and uses hardcoded local `docker compose stop/start` commands to exercise faults. It never grants the web application Docker access. It requires all four services and credentials generated by the initializer. The browser-check helper is tooling only; the application has no Node/frontend framework dependency.

## Publication scan

Gitleaks 8.30.1 scanned the publication files with redacted reporting and found no leaks. The checked-in `scripts/scan-publication.py` additionally compares indexed publication bytes against the generated local passwords and refuses a staged `.env` or build output. Local credentials, caches, build artifacts, and runtime logs are excluded. Secret scanning cannot prove the absence of every possible secret.

GitHub Actions uses the same Maven verify target. Its live result is visible in the repository's Actions tab; a local result alone is not evidence that a hosted run passed.
