# Sample: shop

A small but realistic Spring Boot microservices workspace used by agentideate's tests and benchmark.
It is source only and is never compiled by the agentideate build.

| Service | Build | Talks to |
|---|---|---|
| order-service | Gradle (Groovy) | Postgres (JPA), Kafka producer `order.created`, Feign -> pricing-service |
| inventory-service | Maven | Kafka consumer `order.created`, MongoDB |
| pricing-service | Gradle (Kotlin DSL) | Postgres (JPA) |
