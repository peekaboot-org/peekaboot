INSERT INTO customer_order (reference, customer_id, status, placed_at) VALUES
    ('PK-1001', 1, 'PLACED',    TIMESTAMP WITH TIME ZONE '2026-08-18 09:12:00+00'),
    ('PK-1002', 1, 'SHIPPED',   TIMESTAMP WITH TIME ZONE '2026-08-18 14:40:00+00'),
    ('PK-1003', 2, 'PLACED',    TIMESTAMP WITH TIME ZONE '2026-08-19 08:05:00+00'),
    ('PK-1004', 2, 'CANCELLED', TIMESTAMP WITH TIME ZONE '2026-08-19 16:22:00+00'),
    ('PK-1005', 3, 'SHIPPED',   TIMESTAMP WITH TIME ZONE '2026-08-20 11:01:00+00'),
    ('PK-1006', 3, 'PLACED',    TIMESTAMP WITH TIME ZONE '2026-08-20 17:47:00+00'),
    ('PK-1007', 1, 'DELIVERED', TIMESTAMP WITH TIME ZONE '2026-08-21 07:30:00+00'),
    ('PK-1008', 2, 'PLACED',    TIMESTAMP WITH TIME ZONE '2026-08-21 12:15:00+00');

INSERT INTO order_line (order_id, sku, quantity, unit_price)
SELECT o.id, 'WIDGET-' || ((o.id % 4) + 1), (o.id % 3) + 1, 19.99 + o.id
FROM customer_order o;

INSERT INTO order_line (order_id, sku, quantity, unit_price)
SELECT o.id, 'GADGET-' || ((o.id % 3) + 1), 1, 149.50
FROM customer_order o
WHERE o.status <> 'CANCELLED';
