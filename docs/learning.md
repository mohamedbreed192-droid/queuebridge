# Code-reading guide

1. `Orders.Request` and `Orders.CATALOG`: semantic request identity and BigDecimal prices. Follow `submit` through the unique key conflict path and atomic outbox write.
2. `db/migration/V1__schema.sql`: map the uniqueness and foreign-key constraints to the guarantees the code depends on.
3. `Messaging.publishOne`: inspect SKIP LOCKED, persistent properties, mandatory routing, confirms, and the transaction around the network call. Consider why this is understandable but limited in throughput.
4. `Messaging.receive` and `process`: distinguish broker acknowledgment from database commit, then trace duplicate, retry, rejection, exhaustion, and rollback paths.
5. `Orders.admin`: understand order locking, role enforcement at the HTTP boundary, new replay event IDs, and preserved history.
6. `Security`, `RequestLimits`, and `Web`: login roles, CSRF, payload limits, escaped templates, and SQL-derived metrics.
7. `ReliabilityTest`: inspect actual containers and fault conditions. Read the verification report before describing coverage in an interview.

## Interview questions to practice

- Why can't saving an order and publishing to RabbitMQ be two unrelated operations?
- Why can a publisher confirm still lead to duplicate delivery?
- How does a returned unroutable message differ from a broker nack?
- What happens if the consumer commits and crashes before ack?
- Why must deduplication and the business outcome share a transaction?
- What serializes concurrent idempotency-key submissions? What does the losing transaction return?
- How do you distinguish request idempotency from event deduplication?
- Why does a retry reuse an event ID while an administrative replay creates a new one?
- Why is database-derived pending outbox count different from broker queue depth?
- Which guarantees would break when adding an external fulfillment HTTP call?
- What are the tradeoffs of holding an outbox lock during publish confirmation?
- Why does a classic dead-letter queue still have a crash window?

Explain the transferable enterprise messaging concepts using the RabbitMQ implementation. Do not claim IBM MQ experience from this project.
