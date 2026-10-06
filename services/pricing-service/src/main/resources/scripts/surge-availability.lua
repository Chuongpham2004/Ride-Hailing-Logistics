-- Records a driver's availability for supply counting. Stale or replayed versions are ignored
-- (FR-EVT-006). Any change takes the driver out of the supply sets; the next position report
-- of an AVAILABLE driver puts them back in the right cell.
--
-- KEYS[1] surge:driver:{driverId}
-- ARGV[1] driverId   ARGV[2] aggregateVersion   ARGV[3] availability
-- ARGV[4] service types, comma separated   ARGV[5] supply key prefix   ARGV[6] state TTL (ms)
--
-- Returns 1 when applied, 0 when the event was older than what is stored.

local current = redis.call('HGET', KEYS[1], 'version')
if current and tonumber(current) >= tonumber(ARGV[2]) then
    return 0
end

local cell = redis.call('HGET', KEYS[1], 'cell')
local services = redis.call('HGET', KEYS[1], 'services')
if cell and services then
    for service in string.gmatch(services, '[^,]+') do
        redis.call('ZREM', ARGV[5] .. service .. ':' .. cell, ARGV[1])
    end
end

redis.call('HDEL', KEYS[1], 'cell', 'ts')
redis.call('HSET', KEYS[1], 'version', ARGV[2], 'status', ARGV[3], 'services', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[6])
return 1
