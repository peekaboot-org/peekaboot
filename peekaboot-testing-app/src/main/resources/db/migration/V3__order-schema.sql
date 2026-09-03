CREATE TABLE customer_order (
    id BIGSERIAL PRIMARY KEY,
    reference VARCHAR(32) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL REFERENCES person (id),
    status VARCHAR(20) NOT NULL,
    placed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE order_line (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES customer_order (id),
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL
);

CREATE INDEX idx_order_line_order_id ON order_line (order_id);
