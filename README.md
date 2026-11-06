# ACS Payment

A payment service that integrates with Paystack (sandbox only). Clients start a payment, get a checkout link, and check its status. I keep each payment's status accurate using webhooks, provider verification and a scheduled reconciler.

The design is in [ARCHITECTURE.md](./ARCHITECTURE.md).

## Stack

- Java 25
- Spring Boot 4.1
- Spring Modulith 2.1 (modules and application events)
- PostgreSQL with Flyway migrations
- Testcontainers for tests

## Getting started

You'll need Java 25 and Docker.

Copy `sample.env` to `.env` and put your Paystack test secret key in it.

Run the app:

```bash
./mvnw spring-boot:run
```

Spring Boot's Docker Compose support starts Postgres from `compose.yaml` for you.

Run the tests:

```bash
./mvnw verify
```

Tests start their own Postgres through Testcontainers, so Docker has to be running.

## Status

Early days. The project is scaffolded and the architecture is written up. The payment features are being built next.
