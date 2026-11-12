# Five-minute local demonstration

1. Generate credentials, run tests, package, and start Compose as in the README. Log in as operator. Submit two SENSOR-KIT units with a memorable idempotency key; observe AED 99.80 and fulfillment. Refresh to see the worker's committed timeline.
2. Submit the same key and content again. It opens the same UUID. Change quantity with that key; see 409 conflict. This illustrates request idempotency independently of consumer deduplication.
3. Run `docker compose stop worker`. Submit a new order, sign in as admin, open its detail, select **Deliver same event twice**, supply a reason, and execute. Run `docker compose start worker`. Refresh until FULFILLED with a DUPLICATE observation and one inbox outcome.
4. Repeat with a fresh accepted order and **Fail first processing attempt**. Restart worker and refresh: RETRY, then after at least five seconds FULFILLED. Repeat with **Reject business request**: terminal REJECTED, no replay allowed.
5. Repeat with **Exhaust three processing attempts**. Wait for 5 and 15 second retry delays; observe FAILED and EXHAUSTED. As admin, enter a reason and replay. The audit actor/reason/time persist and a new event is created. Replay clears the synthetic injection and fulfills through a new delivery; at most two replays are allowed. Operator requests for admin actions and requests missing CSRF tokens are rejected.

Always stop the worker before assigning an injection scenario: accepted orders otherwise process quickly. Local lab actions cannot target arbitrary broker resources. Database metrics and timelines update only when the page is refreshed; there are no optimistic status changes.

## Broker outage and recovery

```sh
docker compose stop rabbitmq
# In the browser submit a fresh order; it remains ACCEPTED.
# Inspect pending outbox rows, publish attempt count, and generic retry error.
docker compose logs --tail=40 worker
docker compose start rabbitmq
# Wait for reconnect and publisher retries; refresh the order to see fulfillment.
```

The web container may report a broker health warning; submission only needs PostgreSQL. The web application has no Docker socket and cannot execute these commands. Do not remove volumes during this exercise.

## Inspect dead letters

```sh
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

`queuebridge.dead.v1` holds invalid/exhausted messages. The UI reviews failed **database orders**, not arbitrary malformed broker payloads. A malformed payload is inspected using broker administration, not replayed from browser input. Preserve dead letters for investigation. Authorized replay creates a fresh database event; it does not purge the original evidence.

```sh
docker compose down         # stop, preserve data
# docker compose down -v   # DESTRUCTIVE: deletes the local synthetic demo data
```
