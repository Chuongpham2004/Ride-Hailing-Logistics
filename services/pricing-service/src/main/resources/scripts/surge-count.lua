-- Demand and supply over an area (the pickup cell and its neighbours), read in one round trip.
--
-- KEYS[1..n]    surge:demand:{serviceType}:{cell} for each cell of the area
-- KEYS[n+1..2n] surge:supply:{serviceType}:{cell} for the same cells
-- ARGV[1] n   ARGV[2] oldest request counted (epoch ms)   ARGV[3] oldest position counted (epoch ms)
--
-- Returns {demand, supply}.

local n = tonumber(ARGV[1])
local demand = 0
local supply = 0
for i = 1, n do
    demand = demand + redis.call('ZCOUNT', KEYS[i], ARGV[2], '+inf')
end
for i = n + 1, 2 * n do
    supply = supply + redis.call('ZCOUNT', KEYS[i], ARGV[3], '+inf')
end
return { demand, supply }
