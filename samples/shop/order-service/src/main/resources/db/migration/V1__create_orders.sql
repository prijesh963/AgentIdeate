CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(64)    NOT NULL,
    sku         VARCHAR(64)    NOT NULL,
    quantity    INT            NOT NULL,
    total       NUMERIC(12, 2) NOT NULL
);
