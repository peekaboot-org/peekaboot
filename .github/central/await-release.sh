#!/usr/bin/env bash
#
# Waits until a released version can be downloaded from Maven Central.
#
# The release uploads with waitUntil=uploaded, because central-publishing-maven-plugin 0.11.0
# fails the build on the first non-2xx status poll (StatusPublisherEndpoint.call) even when the
# deployment goes on to publish. Here a failed request is just another retry. A deployment
# publishes all or nothing, so the starter's pom stands for the whole bundle.
#
# Usage: await-release.sh <version>
set -euo pipefail

readonly VERSION="${1:?usage: await-release.sh <version>}"
readonly TIMEOUT_SECONDS="${AWAIT_TIMEOUT_SECONDS:-1800}"
readonly POLL_SECONDS=30
readonly URL="https://repo1.maven.org/maven2/org/peekaboot/peekaboot-spring-boot-starter/${VERSION}/peekaboot-spring-boot-starter-${VERSION}.pom"

readonly DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
until curl --silent --fail --head --max-time 30 --output /dev/null "$URL"; do
    if ((SECONDS >= DEADLINE)); then
        echo "::error::${VERSION} is not on Maven Central after ${TIMEOUT_SECONDS}s; check its deployment at https://central.sonatype.com/publishing/deployments" >&2
        exit 1
    fi
    sleep "$POLL_SECONDS"
done
echo "${VERSION} is on Maven Central"
