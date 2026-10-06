#!/usr/bin/env bash
# Database-per-service (CON-02): one database and one login per service; no login
# can connect to another service's database. Dev-only credentials (<service>/<service>).
set -euo pipefail

for svc in user location trip pricing payment; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE ROLE ${svc}_svc LOGIN PASSWORD '${svc}_svc';
    CREATE DATABASE ${svc}_db OWNER ${svc}_svc;
    REVOKE ALL ON DATABASE ${svc}_db FROM PUBLIC;
    ALTER DATABASE ${svc}_db SET timezone TO 'UTC';
SQL
done
