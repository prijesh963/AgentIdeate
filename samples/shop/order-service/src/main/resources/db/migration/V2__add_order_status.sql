ALTER TABLE orders ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CREATED';
CREATE INDEX idx_orders_customer ON orders (customer_id);
