# Threat model and honest limitations

Assets: order/outbox consistency, unique outcomes, local credentials, audit history. Trust boundaries: browser to Spring Security, application to PostgreSQL, application to RabbitMQ. All products, orders, and fulfillment are synthetic; no customer data or payments exist.

| Threat | Implemented control | Remaining limit |
|---|---|---|
| Anonymous/unauthorized actions | Session login; operator/admin roles; admin routes restricted | Two in-memory demo identities, no enterprise identity lifecycle |
| Cross-site actions/session reuse | CSRF enabled; Spring session fixation protection; 20 minute session timeout; logout POST | Local HTTP only; use HTTPS and secure cookies before any real hosting |
| Credential disclosure | Distinct generated passwords; BCrypt; ignored local .env; no Actuator env endpoint | Local users and Docker administrators can inspect container environment |
| Request/payload abuse | Fixed catalog, quantity/key bounds, 16 KiB JSON/form cap, 4 KiB event validation | No rate limiting; Rabbit consumer receives broker payload before checking size |
| Injection/XSS | Parameterized JDBC; Thymeleaf escaped text; no browser-selected destinations | Trusted DB/broker account compromise is outside the demo threat boundary |
| Duplicate effects | Unique key + inbox, transactional updates, per-order lock | No external-effect guarantee; at-least-once transport |
| Privileged recovery abuse | Admin role, reason, timestamp, max two replays, immutable-by-application history | DB owners can alter history; not tamper-evident compliance logging |
| Broker/network failure | Persistent messages, durable queues, confirms/returns, delayed durable outbox retries | Single-node classic queues, no HA/TLS, DLX loss window; see architecture |

Actuator exposes only health/info and requires admin. No environment values, heap dump, or configuration endpoints are exposed. Demo ports bind to 127.0.0.1, and databases/broker expose no host ports in the normal Compose setup. The worker has no web listener and neither application receives Docker permissions.

Failure injection is controlled on the server by DEMO_ENABLED (false by default). Demo initialization explicitly opts in. The worker uses the same database credentials as web; a real deployment should separate privileges and rotate credentials. No public deployment is supplied. There is no guarantee of high availability, exactly-once delivery, comprehensive observability, audit immutability, or production suitability.
