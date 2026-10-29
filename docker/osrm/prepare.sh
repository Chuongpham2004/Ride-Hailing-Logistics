#!/bin/sh
# Builds the OSRM routing graph for Vietnam once (docker compose profile "map").
# Runs in the osrm-backend image; the extract was downloaded by osrm-download into /data.
# Skipped when the graph is already there; to rebuild with newer map data:
#   docker compose --profile map down && docker volume rm rhl_osrm-data
set -eu

DATA=/data
NAME=vietnam-latest

if [ -f "$DATA/$NAME.osrm.mldgr" ]; then
  echo "OSRM graph already built, nothing to do"
  exit 0
fi

echo "Extracting roads with the car profile (several minutes, needs a few GB of RAM)"
osrm-extract -p /opt/car.lua "$DATA/$NAME.osm.pbf"
osrm-partition "$DATA/$NAME.osrm"
osrm-customize "$DATA/$NAME.osrm"
echo "OSRM graph ready"
