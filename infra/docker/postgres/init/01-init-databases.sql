-- TransferHub: Database initialization
-- Executed automatically on first PostgreSQL start
-- All services use the default 'transferhub' database (POSTGRES_DB)

-- Grant full privileges on the default database
GRANT ALL PRIVILEGES ON DATABASE transferhub TO transferhub;

-- Unleash feature flag server needs its own database
CREATE DATABASE unleash;
