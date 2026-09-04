#!/usr/bin/env python3
"""Download the OpenAddresses PMTiles for one country into a directory.

Resolved at run time from the batch.openaddresses.io API: job URLs expire as
runs rotate, so a job id or pmtiles URL must never be cached or hard-coded.

Selection: the `<cc>/countrywide` source when its latest job produced PMTiles
(~14M G-NAF points in one archive for au); otherwise every per-source archive
the country has (capped — a cap drop is printed, never silent).

Exit status: 0 when at least one archive was downloaded or the country has no
address data (logged — the caller skips the merge), 1 when the country has
sources but not one archive could be fetched (a country silently losing its
whole address layer must fail the build, not pass quietly).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

API_BASE = "https://batch.openaddresses.io/api"
V2_FALLBACK_URL = "https://v2.openaddresses.io/batch-prod/job/{job}/source.pmtiles"
# How many per-source archives the fallback will fetch before dropping the
# rest. AU/NZ/CA/SG all have countrywide archives, so this only ever bites a
# country whose countrywide run failed — the merge still happens, partial.
MAX_FALLBACK_SOURCES = 30
DOWNLOAD_RETRIES = 5
USER_AGENT = "atlas-pmtiles-ci (+https://github.com/danemadsen/atlas)"


def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def download(url, destination):
    """True when [url] is fully on disk at [destination]; False on 404."""
    for attempt in range(DOWNLOAD_RETRIES):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=600) as response, \
                    open(destination, "wb") as out_file:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    out_file.write(chunk)
            return True
        except urllib.error.HTTPError as error:
            if error.code == 404:
                if os.path.exists(destination):
                    os.unlink(destination)
                print(f"::warning::404 (permanent): {url}")
                return False
            print(f"HTTP {error.code} on {url}, attempt {attempt + 1}")
        except (urllib.error.URLError, OSError) as error:
            print(f"{error} on {url}, attempt {attempt + 1}")
        if os.path.exists(destination):
            os.unlink(destination)  # never keep a partial file
        time.sleep(10 * (attempt + 1))
    return False


def pmtiles_url_for(job_id):
    """The job's PMTiles URL; the v2 host mirrors the same path if the
    metadata omits it."""
    metadata = get_json(f"{API_BASE}/job/{job_id}?output=json")
    return metadata.get("pmtiles_url") or V2_FALLBACK_URL.format(job=job_id)


def main():
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <country-code> <output-dir>", file=sys.stderr)
        return 1
    country_code, output_dir = sys.argv[1], sys.argv[2]
    os.makedirs(output_dir, exist_ok=True)

    sources = get_json(f"{API_BASE}/data?output=json")
    in_country = [
        source for source in sources
        if source.get("layer") == "addresses"
        and (source.get("source") or "").startswith(f"{country_code}/")
    ]
    if not in_country:
        print(f"no OpenAddresses sources for {country_code} "
              "(UK-style closed address data) — the merge is skipped")
        return 0

    countrywide = next((
        source for source in in_country
        if source["source"] == f"{country_code}/countrywide"
        and source.get("output", {}).get("pmtiles")
    ), None)
    if countrywide:
        chosen = [countrywide]
    else:
        per_source = [
            source for source in in_country
            if source.get("output", {}).get("pmtiles")
        ]
        if len(per_source) > MAX_FALLBACK_SOURCES:
            print(f"::warning::dropping {len(per_source) - MAX_FALLBACK_SOURCES} "
                  f"of {len(per_source)} per-source archives "
                  f"(cap {MAX_FALLBACK_SOURCES}); the merge is partial")
        chosen = per_source[:MAX_FALLBACK_SOURCES]

    downloaded = 0
    for source in chosen:
        name = source["source"].replace("/", "-") + ".pmtiles"
        destination = os.path.join(output_dir, name)
        try:
            url = pmtiles_url_for(source["latest_job"])
        except (urllib.error.URLError, OSError, ValueError) as error:
            print(f"::warning::could not resolve {source['source']}: {error}")
            continue
        print(f"fetching {source['source']} ({source.get('size', '?')} bytes)")
        if download(url, destination):
            downloaded += 1
        else:
            print(f"::warning::{source['source']} unavailable; the merge is partial")

    # Zero downloads must fail the job — a country silently losing its
    # entire address layer is worse than a red build. Partial per-source
    # failures keep the job green with the drop warned above.
    if downloaded == 0:
        print(f"::error::no OpenAddresses PMTiles downloaded for {country_code}")
        return 1
    print(f"downloaded {downloaded} archive(s) for {country_code}")
    return 0


if __name__ == "__main__":
    sys.exit(main())