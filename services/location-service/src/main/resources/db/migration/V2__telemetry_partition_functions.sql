-- Partition maintenance for telemetry_history as database functions, so the application only
-- passes dates as bind parameters and never builds DDL strings itself. Identifiers and literals
-- are quoted with format(%I / %L).

-- Creates the partition holding one UTC day; a no-op when it already exists.
CREATE FUNCTION ensure_telemetry_partition(day DATE) RETURNS VOID
    LANGUAGE plpgsql AS
$$
BEGIN
    EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF telemetry_history FOR VALUES FROM (%L) TO (%L)',
            'telemetry_history_p' || to_char(day, 'YYYYMMDD'),
            (day::timestamp AT TIME ZONE 'UTC'),
            ((day + 1)::timestamp AT TIME ZONE 'UTC'));
END;
$$;

-- Drops every daily partition older than oldest_kept; returns how many were dropped.
CREATE FUNCTION drop_telemetry_partitions_before(oldest_kept DATE) RETURNS INTEGER
    LANGUAGE plpgsql AS
$$
DECLARE
    partition_name TEXT;
    dropped        INTEGER := 0;
BEGIN
    FOR partition_name IN
        SELECT c.relname
        FROM pg_inherits i
                 JOIN pg_class c ON c.oid = i.inhrelid
                 JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'telemetry_history'
          AND c.relname ~ '^telemetry_history_p[0-9]{8}$'
          AND to_date(substring(c.relname FROM '[0-9]{8}$'), 'YYYYMMDD') < oldest_kept
        LOOP
            EXECUTE format('DROP TABLE IF EXISTS %I', partition_name);
            dropped := dropped + 1;
        END LOOP;
    RETURN dropped;
END;
$$;
