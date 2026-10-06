-- Counts one trip request in its pickup cell. The trip ID is the member, so a redelivered
-- TripRequested is counted once. Entries older than the window are trimmed on every write.
--
-- KEYS[1] surge:demand:{serviceType}:{cell}
-- ARGV[1] tripId   ARGV[2] request time (epoch ms)   ARGV[3] window (ms)

local ts = tonumber(ARGV[2])
local window = tonumber(ARGV[3])
redis.call('ZADD', KEYS[1], ts, ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (ts - window))
redis.call('PEXPIRE', KEYS[1], window * 2)
return 1
