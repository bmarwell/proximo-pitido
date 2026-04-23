#!/bin/bash
# Próximo Pitido — update and redeploy
#
# Usage: ./update.sh [-f]
#   -f   force rebuild even when no new commits are available
#
# Requirements: docker (with BuildKit), git, internet access to github.com
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
cd "${SCRIPT_DIR}"

FORCE="${1:-}"

# Resolve the current HEAD SHA on the configured branch without cloning the full repository.
# Docker uses this as a cache-bust key: only the git clone layer (and those
# after it) are rebuilt when the branch has new commits.  Layers before the clone
# (base image pull, native-library install) remain cached.
GIT_REF="${GIT_REF:-debug/add-rtp-encoding-trace}"

HEAD_SHA=$(git ls-remote https://github.com/bmarwell/proximo-pitido.git "${GIT_REF}" | cut -f1)

if [[ -z "${HEAD_SHA}" ]]; then
    echo "Could not resolve HEAD SHA from GitHub — aborting." >&2
    exit 1
fi

# Read the SHA used for the last successful build, if any.
LAST_SHA_FILE="${SCRIPT_DIR}/.last-built-sha"
LAST_SHA=""

if [[ -f "${LAST_SHA_FILE}" ]]; then
    LAST_SHA=$(cat "${LAST_SHA_FILE}")
fi

if [[ "${HEAD_SHA}" == "${LAST_SHA}" ]] && [[ "${FORCE}" != "-f" ]]; then
    echo "Already at HEAD ${HEAD_SHA} — nothing to do.  Pass -f to force a rebuild."
    exit 0
fi

echo "Building HEAD ${HEAD_SHA}"

docker compose build \
    --build-arg "CACHE_BUST=${HEAD_SHA}" \
    --build-arg "GIT_REF=${GIT_REF}"

docker compose up --no-color -d --force-recreate

echo "${HEAD_SHA}" > "${LAST_SHA_FILE}"
echo "Done."
