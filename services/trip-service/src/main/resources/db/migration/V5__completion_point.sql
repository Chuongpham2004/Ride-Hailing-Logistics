-- Where a trip was completed: the driver's last known position at that moment (location-service)
-- and its distance to the planned drop-off. NULL when no position was known; support reviews
-- trips completed far from the drop-off (rhl.completion.max-distance-meters).
ALTER TABLE trips
    ADD COLUMN completion_latitude        DOUBLE PRECISION,
    ADD COLUMN completion_longitude       DOUBLE PRECISION,
    ADD COLUMN completion_distance_meters INTEGER,
    ADD CONSTRAINT ck_trips_completion_point CHECK (
        (completion_latitude IS NULL AND completion_longitude IS NULL AND completion_distance_meters IS NULL)
        OR (completion_latitude BETWEEN -90 AND 90 AND completion_longitude BETWEEN -180 AND 180
            AND completion_distance_meters >= 0));
