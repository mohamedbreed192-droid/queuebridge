# Repository and verification

Source: https://github.com/mohamedbreed192-droid/queuebridge

The repository contains the application, container tests, workflow, documentation, and real screenshots. Generated local credentials and build artifacts are excluded. No public application deployment is configured.

GitHub Actions runs `./mvnw --batch-mode verify` on Java 21. Inspect the [Verify QueueBridge workflow](https://github.com/mohamedbreed192-droid/queuebridge/actions/workflows/verify.yml) for the latest hosted result. The [verification report](verification.md) records the local container and browser checks.

To get the project:

```sh
git clone https://github.com/mohamedbreed192-droid/queuebridge.git
cd queuebridge
```

Follow the README to generate distinct local credentials and start the Docker Compose demo. Never commit `.env` or send access tokens in chat.
