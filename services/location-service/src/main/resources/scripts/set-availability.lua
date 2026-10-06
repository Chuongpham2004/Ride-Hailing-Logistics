-- Puts a driver into (or takes them out of) the GEO index after an availability change. Only
-- AVAILABLE drivers with a live position are indexed (README §4.6).
--
-- KEYS[1] loc:avail:{driverId}     KEYS[2] loc:driver:{driverId}     KEYS[3] loc:seq:{driverId}
-- ARGV[1] driverId   ARGV[2] matchable service types, comma separated ('' = not matchable)
-- ARGV[3] vehicleId ('' = none)   ARGV[4] every service type, comma separated
-- ARGV[5] '1' when a new online session starts: the app restarts its sequence numbering
--
-- Returns 1 when the driver is now in the GEO index, 0 otherwise.

local driverId = ARGV[1]
if ARGV[5] == '1' then
  redis.call('DEL', KEYS[3])
end
for serviceType in string.gmatch(ARGV[4], '[^,]+') do
  redis.call('ZREM', 'geo:drivers:' .. serviceType, driverId)
  redis.call('ZREM', 'geo:lastseen:' .. serviceType, driverId)
end

if ARGV[2] == '' then
  redis.call('DEL', KEYS[1])
  return 0
end
redis.call('HSET', KEYS[1], 'serviceTypes', ARGV[2], 'vehicleId', ARGV[3])

-- Without a live position the driver joins the index on the next telemetry report.
local position = redis.call('HMGET', KEYS[2], 'lat', 'lng', 'serverTs')
if not position[1] or math.abs(tonumber(position[1])) > 85.05112878 then
  return 0
end
for serviceType in string.gmatch(ARGV[2], '[^,]+') do
  redis.call('GEOADD', 'geo:drivers:' .. serviceType, position[2], position[1], driverId)
  redis.call('ZADD', 'geo:lastseen:' .. serviceType, position[3], driverId)
end
return 1
