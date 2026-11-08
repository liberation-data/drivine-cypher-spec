#!/usr/bin/env bash
# Uploads the signed bundle compiler-release-sign.sh made to the Central Portal, as nmcp's
# publishAggregatedPublicationToCentralPortal did: one deployment, published once it validates (AUTOMATIC), so the
# Portal releases every artifact in it or none. Then follows the deployment until it is published, and fails where the
# Portal fails it, with the Portal's reasons.
#   compiler-release-upload.sh <signed bundle zip>
# The Portal token is CENTRAL_PORTAL_USERNAME and CENTRAL_PORTAL_PASSWORD. It is sent in a header read from a file this
# script makes and deletes, so it is never on a command line, and nothing here writes it to the log.
set -euo pipefail
bundle="$1"
portal="${CENTRAL_PORTAL_URL:-https://central.sonatype.com/api/v1/publisher}"
fail() { echo "::error::$*"; exit 1; }
[[ -n "${CENTRAL_PORTAL_USERNAME:-}" && -n "${CENTRAL_PORTAL_PASSWORD:-}" ]] || fail "CENTRAL_PORTAL_USERNAME and CENTRAL_PORTAL_PASSWORD aren't set."
[[ -f "$bundle" ]] || fail "$bundle isn't there."

work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compiler-upload.XXXXXX")
trap 'rm -rf "$work"' EXIT
headers="$work/headers"
token=$(printf '%s:%s' "$CENTRAL_PORTAL_USERNAME" "$CENTRAL_PORTAL_PASSWORD" | base64 | tr -d '\n')
# GitHub masks the secrets, not their base64: mask it too, in case the Portal's answer quotes it.
[[ -z "${GITHUB_ACTIONS:-}" ]] || echo "::add-mask::$token"
( umask 077; printf 'Authorization: UserToken %s\n' "$token" > "$headers" )

# The deployment's id, as the Portal answers an upload. Sent once, not retried: an upload the Portal took, whose answer
# was lost, would make a second deployment of the version, which fails as the first is published.
status=$(curl -sS --max-time 1200 -o "$work/upload" -w '%{http_code}' -H @"$headers" -F "bundle=@$bundle;type=application/zip;filename=publicationAggregated.zip" \
    "$portal/upload?publishingType=AUTOMATIC") || fail "Couldn't reach the Central Portal to upload: see https://central.sonatype.com/publishing/deployments before re-running, in case it took the upload."
[[ "$status" == 201 || "$status" == 200 ]] || fail "The Central Portal refused the upload (HTTP $status): $(head -c 2000 "$work/upload")"
deployment=$(tr -d '[:space:]' < "$work/upload")
[[ "$deployment" =~ ^[0-9a-fA-F-]{8,}$ ]] || fail "The Central Portal answered the upload with no deployment id: $(head -c 500 "$work/upload")"
echo "Uploaded as deployment $deployment."

# Until the Portal has published it, or failed it: for at most an hour, as Maven Central's validation can take a while.
state=""
for _ in $(seq 1 120); do
    status=$(curl -sS --max-time 60 --retry 3 --retry-all-errors --retry-delay 10 -o "$work/status" -w '%{http_code}' -H @"$headers" \
        -X POST "$portal/status?id=$deployment") || status=000
    if [[ "$status" == 200 ]]; then
        # An answer that isn't JSON (a proxy's error page) is asked again, as any other.
        state=$(jq -r '.deploymentState // empty' "$work/status" 2> /dev/null) || state=
        case "$state" in
            PUBLISHED) echo "Deployment $deployment is published."; break ;;
            FAILED) fail "The Central Portal failed deployment $deployment: $(jq -c '.errors // .' "$work/status" | head -c 4000)" ;;
            *) echo "Deployment $deployment: ${state:-no state yet}." ;;
        esac
    else
        echo "::warning::The Central Portal answered HTTP $status asking after deployment $deployment; asking again."
    fi
    sleep 30
done
[[ "$state" == PUBLISHED ]] || fail "Deployment $deployment wasn't published within an hour (last state: ${state:-unknown}). See it at https://central.sonatype.com/publishing/deployments."
{
    echo "### Published to Maven Central"
    echo "Deployment \`$deployment\`."
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
