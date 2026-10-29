-- Road route of a quote as an encoded polyline (precision 6) when it came from OSRM (TBD-02).
-- NULL for the straight-line estimate and for quotes issued before OSRM.
ALTER TABLE fare_quotes ADD COLUMN route_polyline TEXT;
