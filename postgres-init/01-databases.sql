-- Per-service databases (schema-per-service, one Postgres instance locally).
-- NOTE: docker-entrypoint-initdb.d runs ONLY when the volume is first
-- created. After changes, run `make reset` (docker compose down -v).
CREATE DATABASE aegis_ingest;  -- ingest-svc: anomalies
-- aegis: incident-svc (created by POSTGRES_DB env)