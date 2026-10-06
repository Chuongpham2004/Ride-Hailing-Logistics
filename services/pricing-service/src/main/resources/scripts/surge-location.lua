-- Counts an AVAILABLE driver as supply in the cell of their latest position, removing them from
-- the cell they left. Older or duplicate positions are ignored, so a late report never moves a
-- driver back. Entries older than the freshness window are trimmed on every write.
--
-- KEYS[1] surge:driver:{driverId}
-- ARGV[1] driverId   ARGV[2] H3 cell   ARGV[3] position time (epoch ms)
-- ARGV[4] supply key prefix   ARGV[5] freshness (ms)   ARGV[6] state TTL (ms)
--
-- Returns 1 when counted, 0 when ignored.

if redis.call('HGET', KEYS[1], 'status') ~= 'AVAILABLE' then
    return 0
end
local last = redis.call('HGET', KEYS[1], 'ts')
if last and tonumber(last) >= tonumber(ARGV[3]) then
    return 0
end

local ts = tonumber(ARGV[3])
local freshness = tonumber(ARGV[5])
local previous = redis.call('HGET', KEYS[1], 'cell')
local services = redis.call('HGET', KEYS[1], 'services') or ''
for service in string.gmatch(services, '[^,]+') do
    if previous and previous ~= ARGV[2] then
        redis.call('ZREM', ARGV[4] .. service .. ':' .. previous, ARGV[1])
    end
    local key = ARGV[4] .. service .. ':' .. ARGV[2]
    redis.call('ZADD', key, ts, ARGV[1])
    redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. (ts - freshness))
    redis.call('PEXPIRE', key, freshness * 2)
end

redis.call('HSET', KEYS[1], 'ts', ARGV[3], 'cell', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[6])
return 1
