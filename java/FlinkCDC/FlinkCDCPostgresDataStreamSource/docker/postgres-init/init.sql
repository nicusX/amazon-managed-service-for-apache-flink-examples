-- Create shipments table
CREATE TABLE shipments (
    shipment_id INT PRIMARY KEY,
    order_id INT,
    origin VARCHAR(255),
    destination VARCHAR(255),
    is_arrived BOOLEAN
);

-- Insert example shipment records
INSERT INTO shipments (shipment_id, order_id, origin, destination, is_arrived) VALUES
(1001, 5001, 'New York, NY', 'Los Angeles, CA', false),
(1002, 5002, 'Chicago, IL', 'Miami, FL', true),
(1003, 5003, 'Seattle, WA', 'Denver, CO', false),
(1004, 5004, 'Boston, MA', 'Austin, TX', true);


