-- TransferHub: Database initialization
-- Executed automatically on first PostgreSQL start

-- Transfer Service database
CREATE DATABASE transfer_db;

-- Outbox Service database (separate for independent scaling)
CREATE DATABASE outbox_db;

-- Pricing Service database (for future persistent storage)
CREATE DATABASE pricing_db;

-- Grant all privileges to the main user
GRANT ALL PRIVILEGES ON DATABASE transfer_db TO transferhub;
GRANT ALL PRIVILEGES ON DATABASE outbox_db TO transferhub;
GRANT ALL PRIVILEGES ON DATABASE pricing_db TO transferhub;

-- Connect to transfer_db and create schema
\c transfer_db
CREATE SCHEMA IF NOT EXISTS transfers;
GRANT ALL ON SCHEMA transfers TO transferhub;

-- Connect to outbox_db and create schema
\c outbox_db
CREATE SCHEMA IF NOT EXISTS outbox;
GRANT ALL ON SCHEMA outbox TO transferhub;
