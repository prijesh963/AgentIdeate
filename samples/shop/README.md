# Sample: shop

A small but realistic Spring Boot microservices workspace used by agentideate's tests and benchmark.
It is source only and is never compiled by the agentideate build.

| Service | Build | Talks to |
|---|---|---|
| order-service | Gradle (Groovy) | Postgres (JPA, Flyway migrations), Kafka producers `order.created` / `order.cancelled`, Feign -> pricing-service |
| inventory-service | Maven | Kafka consumers `order.created` and `order.cancelled` (topic from `application.yml`), MongoDB |
| pricing-service | Gradle (Kotlin DSL) | Postgres (JPA, plus a `JdbcTemplate` DAO) |
