# Publish QueueBridge manually

The project is committed locally. GitHub rejected automated creation of `mohamedbreed192-droid/queuebridge` with:

```text
GraphQL: Resource not accessible by integration (createRepository)
```

No repository was created and no hosted Actions run occurred. The other repositories and profile were not modified. Do not send access tokens in chat.

## Exact manual steps

1. Sign in to GitHub as **mohamedbreed192-droid** and open https://github.com/new.
2. Choose owner **mohamedbreed192-droid**, name **queuebridge**, visibility **Public**. Leave README, license, and gitignore initialization unchecked; create the empty repository.
3. Download `queuebridge.bundle`, open a terminal in its containing directory, and run:

```sh
git clone queuebridge.bundle queuebridge
cd queuebridge
git remote set-url origin https://github.com/mohamedbreed192-droid/queuebridge.git
git push -u origin main
```

Authenticate using your normal local GitHub credential flow. The bundle preserves the project commits, including code, tests, documentation, screenshots, and workflow. It excludes generated credentials and build artifacts. The ZIP is a convenient source-only alternative; it does not preserve Git history.

Alternatively, from the already-created `/workspace/queuebridge` checkout in the original Codex environment:

```sh
git remote add origin https://github.com/mohamedbreed192-droid/queuebridge.git
git push -u origin main
```

That checkout has no remote because repository creation failed. If a remote already exists when you follow these instructions, use `git remote set-url origin` instead.

## Repository metadata

Description:

> Java 21 order-processing portfolio: PostgreSQL transactional outbox, RabbitMQ, inbox deduplication, failure recovery, and tested local demo.

Suggested topics: `java`, `spring-boot`, `postgresql`, `rabbitmq`, `transactional-outbox`, `idempotency`, `testcontainers`, `portfolio`.

Set these in the repository's About panel, or with your authorized local CLI after creating the repository:

```sh
gh repo edit mohamedbreed192-droid/queuebridge --description 'Java 21 order-processing portfolio: PostgreSQL transactional outbox, RabbitMQ, inbox deduplication, failure recovery, and tested local demo.' --add-topic java,spring-boot,postgresql,rabbitmq,transactional-outbox,idempotency,testcontainers,portfolio
```

After pushing, inspect the **Verify QueueBridge** workflow in Actions. Local tests passed; a hosted run is still required to establish the hosted CI result. The intended repository address is https://github.com/mohamedbreed192-droid/queuebridge. No public application deployment is part of this project.
