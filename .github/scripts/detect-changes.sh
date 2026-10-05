#!/usr/bin/env bash
# Decides what the pipeline has to build for the current event.
#
# Inputs (env):
#   BASE_SHA   commit to diff against (PR base or push "before"); empty/zero => full build
#   FORCE_ALL  "true" to build everything (manual runs, tags)
#
# Outputs (appended to $GITHUB_OUTPUT):
#   has-maven      "true" when a root pom.xml exists
#   has-contracts  "true" when contracts/ exists
#   build-needed   "true" when at least one Maven module must be built
#   maven-pl       comma-separated modules for `mvn -pl` ("" => whole reactor)
#   services       JSON array of affected services (services/<name>/pom.xml)
set -euo pipefail

BASE_SHA="${BASE_SHA:-}"
FORCE_ALL="${FORCE_ALL:-false}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

has_maven=false
[[ -f pom.xml ]] && has_maven=true
has_contracts=false
[[ -d contracts ]] && has_contracts=true

all_services=()
if [[ -d services ]]; then
  while IFS= read -r pom; do
    all_services+=("$(basename "$(dirname "$pom")")")
  done < <(find services -mindepth 2 -maxdepth 2 -name pom.xml | sort)
fi

# Shared code, build config and contracts affect every service.
full=false
changed=""
if [[ "$FORCE_ALL" == "true" || -z "$BASE_SHA" || "$BASE_SHA" =~ ^0+$ ]] \
  || ! git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null; then
  full=true
else
  changed="$(git diff --name-only "$BASE_SHA" HEAD)"
  if grep -qE '^(pom\.xml|\.mvn/|mvnw|libs/|contracts/|tools/|docker/|\.github/)' <<<"$changed"; then
    full=true
  fi
fi

affected=()
for svc in "${all_services[@]}"; do
  if [[ "$full" == "true" ]] || grep -q "^services/${svc}/" <<<"$changed"; then
    affected+=("$svc")
  fi
done

build_needed=false
maven_pl=""
if [[ "$has_maven" == "true" ]]; then
  if [[ "$full" == "true" ]]; then
    build_needed=true
  elif ((${#affected[@]})); then
    build_needed=true
    maven_pl="$(printf 'services/%s,' "${affected[@]}")"
    maven_pl="${maven_pl%,}"
  fi
fi

services_json="[]"
if ((${#affected[@]})); then
  # Directory names are plain identifiers, so no JSON escaping is needed.
  services_json="$(printf '"%s",' "${affected[@]}")"
  services_json="[${services_json%,}]"
fi

{
  echo "has-maven=${has_maven}"
  echo "has-contracts=${has_contracts}"
  echo "build-needed=${build_needed}"
  echo "maven-pl=${maven_pl}"
  echo "services=${services_json}"
} >>"$OUT"

echo "full=${full} has-maven=${has_maven} build-needed=${build_needed} services=${services_json}"
