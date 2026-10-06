-- Removes drivers whose last position is older than the cutoff from one GEO index (FR-LOC-008).
-- Redis GEO has no per-member TTL, so geo:lastseen:{type} tracks when each member was refreshed.
--
-- KEYS[1] geo:drivers:{type}     KEYS[2] geo:lastseen:{type}
-- ARGV[1] cutoff (epoch ms)      ARGV[2] max members to remove in one call
--
-- Returns the number of members removed.

local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[1], 'LIMIT', 0, ARGV[2])
if #stale == 0 then
  return 0
end
redis.call('ZREM', KEYS[1], unpack(stale))
redis.call('ZREM', KEYS[2], unpack(stale))
return #stale
