#!/usr/bin/env bash
# Download a Geofabrik extract, tolerating its real-world failure modes:
#   - dropped connections mid-body on the multi-GB extracts — retried and
#     RESUMED (--continue-at -) instead of restarting 11 GB from zero;
#   - incident-window HTML error pages (a 200 with a body full of HTML) —
#     --fail cannot catch those, so the OSM pbf magic byte does: every
#     extract starts with 0x0a (a BlobHeader record), an error page with
#     '<'.
set -euo pipefail

url=$1
out=$2
MIN_BYTES=100000

mkdir -p "$(dirname "$out")"
curl --fail --location --retry 8 --retry-delay 10 --retry-all-errors \
     --continue-at - --output "$out" "$url"

first=$(od -An -tx1 -N1 "$out" | tr -d ' \n')
if [ "$first" != "0a" ]; then
  echo "::error::$out is not an OSM pbf (first byte 0x$first) — Geofabrik error page? $(head -c 200 "$out" | tr '\n' ' ')"
  exit 1
fi
size=$(wc -c < "$out")
if [ "$size" -lt "$MIN_BYTES" ]; then
  echo "::error::$out is only $size bytes (floor $MIN_BYTES) — Geofabrik error page?"
  exit 1
fi
echo "$out: $size bytes, valid OSM pbf header"