-- Applies one telemetry report atomically (README §4.6): drop duplicate/older sequences, store the
-- latest position with a TTL and, if the driver is matchable, refresh the GEO index.
--
-- KEYS[1] loc:seq:{driverId}     KEYS[2] loc:driver:{driverId}     KEYS[3] loc:avail:{driverId}
-- ARGV[1] driverId   ARGV[2] sequence   ARGV[3] '1' to update the current position, '0' history only
-- ARGV[4] lat  ARGV[5] lng  ARGV[6] accuracy  ARGV[7] heading ('' = none)  ARGV[8] speed ('' = none)
-- ARGV[9] device time (epoch ms)  ARGV[10] server time (epoch ms)
-- ARGV[11] location TTL (ms)  ARGV[12] sequence TTL (ms)
--
-- geo:drivers:{type} / geo:lastseen:{type} are derived from loc:avail and not passed as KEYS:
-- fine on a single Redis node (v1.0); a cluster would need hash tags per service type.
--
-- Returns 0 = duplicate or older sequence (ignored), 1 = sequence recorded only, 2 = current position updated.

local last = redis.call('GET', KEYS[1])
if last and tonumber(last) >= tonumber(ARGV[2]) then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[12])

if ARGV[3] ~= '1' then
  return 1
end

local driverId = ARGV[1]
local lat, lng = ARGV[4], ARGV[5]
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[2], 'lat', lat, 'lng', lng, 'acc', ARGV[6], 'seq', ARGV[2],
  'deviceTs', ARGV[9], 'serverTs', ARGV[10])
if ARGV[7] ~= '' then redis.call('HSET', KEYS[2], 'heading', ARGV[7]) end
if ARGV[8] ~= '' then redis.call('HSET', KEYS[2], 'speed', ARGV[8]) end
redis.call('PEXPIRE', KEYS[2], ARGV[11])

-- Redis GEO only covers latitudes within +/-85.05112878.
local types = redis.call('HGET', KEYS[3], 'serviceTypes')
if types and math.abs(tonumber(lat)) <= 85.05112878 then
  for serviceType in string.gmatch(types, '[^,]+') do
    redis.call('GEOADD', 'geo:drivers:' .. serviceType, lng, lat, driverId)
    redis.call('ZADD', 'geo:lastseen:' .. serviceType, ARGV[10], driverId)
  end
end
return 2
