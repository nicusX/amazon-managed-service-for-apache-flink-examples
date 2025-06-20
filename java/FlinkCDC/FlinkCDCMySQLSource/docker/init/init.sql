CREATE USER 'flinkusr'@'%' IDENTIFIED BY 'flinkpw';
GRANT SELECT, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'flinkusr'@'%';

FLUSH PRIVILEGES;

-- Create orders table
CREATE TABLE orders (
    order_id INT PRIMARY KEY,
    order_date TIMESTAMP(0),
    customer_name VARCHAR(255),
    product_id INT,
    price DECIMAL(10,5),
    order_status BOOLEAN
);

-- Insert records into orders table
INSERT INTO orders VALUES
(1, '2025-06-16 11:01:00', 'My first customer', 42, 27.35, true),
(2, '2025-06-16 11:03:00', 'Second customer', 43, 13.76, false);
