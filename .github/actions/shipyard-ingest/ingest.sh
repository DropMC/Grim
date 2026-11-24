#!/usr/bin/env bash
# Sends a finished build's jars to Shipyard: start the build, upload each jar, finish it.
# Any failure after `start` reports the build as failed before exiting, so Shipyard never keeps
# a half-uploaded build open as if it might still succeed.
#
#   SHIPYARD_URL     https://deploy.dropmc.gg
#   SHIPYARD_TOKEN   the source's ingest token (shy_...)
#   SHIPYARD_BUILD   build id from the panel's dispatch; empty for a push build
#   COMMIT REF       full commit SHA and the ref that was built
#   RUN_ID RUN_URL   optional: the workflow run, for the link in the panel
#   COMMONS          optional: commons version the jars were compiled against
#
#   ingest.sh path/to/a.jar path/to/b.jar ...
set -euo pipefail

: "${SHIPYARD_URL:?}" "${SHIPYARD_TOKEN:?}" "${COMMIT:?}" "${REF:?}"
[ "$#" -gt 0 ] || { echo "ingest.sh: no jars given" >&2; exit 2; }
for jar in "$@"; do
  [ -f "$jar" ] || { echo "ingest.sh: $jar does not exist" >&2; exit 2; }
done

# The token goes to curl through a file, not its command line, where any process on the runner
# could read it.
auth_header=$(mktemp)
chmod 600 "$auth_header"
printf 'Authorization: Bearer %s\n' "$SHIPYARD_TOKEN" >"$auth_header"

# Prints the response body; on an HTTP error, prints Shipyard's message to stderr and fails.
# RETRY (default 3) is curl's --retry: it repeats transient failures (timeouts, 429, 5xx), never
# a 4xx refusal. api runs inside $(...), so the last error goes through a file to reach the ERR trap.
last_error=$(mktemp)
trap 'rm -f "$auth_header" "$last_error"' EXIT
api() {
  local method=$1 path=$2 out message
  shift 2
  if ! out=$(curl --fail-with-body --silent --show-error --retry "${RETRY:-3}" --max-time 600 \
    -X "$method" -H "@$auth_header" "$@" "${SHIPYARD_URL%/}$path"); then
    message=$(jq -r '.error.message? // .' <<<"$out" 2>/dev/null || echo "$out")
    printf '%s %s: %s' "$method" "$path" "$message" >"$last_error"
    echo "shipyard: $method $path failed: $message" >&2
    return 1
  fi
  printf '%s' "$out"
}

start=$(jq -n \
  --arg commit "$COMMIT" --arg ref "$REF" \
  --arg build "${SHIPYARD_BUILD:-}" --arg runId "${RUN_ID:-}" \
  --arg runUrl "${RUN_URL:-}" --arg commons "${COMMONS:-}" \
  '{commit: $commit, ref: $ref}
   + (if $build != "" then {build: ($build | tonumber)} else {} end)
   + (if $runId != "" then {runId: ($runId | tonumber)} else {} end)
   + (if $runUrl != "" then {runUrl: $runUrl} else {} end)
   + (if $commons != "" then {commons: $commons} else {} end)')
# Not retried: a 5xx after Shipyard created the build would open a second, orphan build.
response=$(RETRY=0 api POST /ingest/builds -H 'Content-Type: application/json' -d "$start")
build=$(jq -r .build <<<"$response")
echo "shipyard build #$build"

finish() {
  local status=$1 error=${2:-}
  api POST "/ingest/builds/$build/finish" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg s "$status" --arg e "$error" '{status: $s} + (if $e != "" then {error: $e} else {} end)')" \
    >/dev/null
}
trap 'finish failed "$(cat "$last_error" 2>/dev/null || true)" || true' ERR

# One request per jar keeps each upload small and makes a retry cheap.
for jar in "$@"; do
  response=$(api POST "/ingest/builds/$build/artifacts" -F "file=@$jar")
  jq -r '.[] | "  \(.fileName)  \(.plugin.name // "-") \(.plugin.version // "")  \(.size) bytes"' <<<"$response"
done

trap - ERR
finish succeeded
echo "shipyard build #$build succeeded with $# jar(s)"
