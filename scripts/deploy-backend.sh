#!/usr/bin/env bash
#
# Deploy one of the Node backends to the box that runs it under systemd.
#
# This script NEVER runs by itself. It is invoked either by hand, or by the
# `Backend Deploy` GitHub Actions workflow, which is `workflow_dispatch`-only
# (see .github/workflows/backend-deploy.yml). There is no push trigger anywhere
# that reaches this file.
#
# Usage:
#   DEPLOY_HOST=... DEPLOY_USER=root DEPLOY_PATH=/srv/busbet \
#     scripts/deploy-backend.sh gtfsr-stop-times
#
# Required environment:
#   DEPLOY_HOST   hostname or IP of the target box     (no default, on purpose)
#   DEPLOY_USER   ssh user                             (default: root)
#   DEPLOY_PATH   parent dir on the box that holds the service directories
#
# Optional environment:
#   SERVICE_UNIT  systemd unit name  (default: "<service>.service")
#   HEALTH_PORT   loopback port to probe after restart (default: per-service)
#   SSH_OPTS      extra ssh options
#   DRY_RUN=1     print what would happen; touch nothing remote
#
# What it does, in order:
#   1. rsync the service directory up to a staging dir (code only — never
#      node_modules, data/, .env)
#   2. snapshot the currently deployed tree so a bad deploy can be undone
#   3. swap staging into place, `npm ci`, restart the unit
#   4. probe GET /health on loopback; if that fails, restore the snapshot,
#      restart again, and exit non-zero
#
set -euo pipefail

SERVICE="${1:-}"
if [[ -z "$SERVICE" ]]; then
	echo "usage: $0 <gtfsr-stop-times|tfi-tenant-api>" >&2
	exit 2
fi

case "$SERVICE" in
	gtfsr-stop-times) DEFAULT_PORT=8110 ;;
	tfi-tenant-api)   DEFAULT_PORT=8120 ;;
	*)
		echo "unknown service: $SERVICE (expected gtfsr-stop-times or tfi-tenant-api)" >&2
		exit 2
		;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$REPO_ROOT/$SERVICE"
[[ -d "$SRC_DIR" ]] || { echo "no such directory: $SRC_DIR" >&2; exit 2; }

DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_HOST="${DEPLOY_HOST:-}"
DEPLOY_PATH="${DEPLOY_PATH:-}"
SERVICE_UNIT="${SERVICE_UNIT:-$SERVICE.service}"
HEALTH_PORT="${HEALTH_PORT:-$DEFAULT_PORT}"
DRY_RUN="${DRY_RUN:-0}"

if [[ -z "$DEPLOY_HOST" || -z "$DEPLOY_PATH" ]]; then
	echo "DEPLOY_HOST and DEPLOY_PATH must both be set. Refusing to guess a target." >&2
	exit 2
fi

TARGET="$DEPLOY_USER@$DEPLOY_HOST"
LIVE="$DEPLOY_PATH/$SERVICE"
STAGE="$DEPLOY_PATH/.staging/$SERVICE"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SNAPSHOT="$DEPLOY_PATH/.rollback/$SERVICE-$STAMP"

# shellcheck disable=SC2206
SSH=(ssh -o BatchMode=yes ${SSH_OPTS:-} "$TARGET")

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

if [[ "$DRY_RUN" == "1" ]]; then
	say "DRY RUN — would deploy $SERVICE to $TARGET:$LIVE (unit $SERVICE_UNIT, health :$HEALTH_PORT)"
	rsync -avzn --delete \
		-e "ssh -o BatchMode=yes ${SSH_OPTS:-}" \
		--exclude node_modules --exclude data --exclude '.env' --exclude '*.db' \
		"$SRC_DIR/" "$TARGET:$STAGE/"
	exit 0
fi

say "Staging $SERVICE -> $TARGET:$STAGE"
"${SSH[@]}" "mkdir -p '$STAGE' '$DEPLOY_PATH/.rollback'"
rsync -avz --delete \
	-e "ssh -o BatchMode=yes ${SSH_OPTS:-}" \
	--exclude node_modules --exclude data --exclude '.env' --exclude '*.db' \
	"$SRC_DIR/" "$TARGET:$STAGE/"

say "Snapshotting current deployment for rollback"
"${SSH[@]}" bash -seu <<REMOTE
if [ -d '$LIVE' ]; then
	mkdir -p '$SNAPSHOT'
	rsync -a --delete --exclude node_modules '$LIVE/' '$SNAPSHOT/'
	echo "snapshot: $SNAPSHOT"
else
	echo "no existing deployment at $LIVE — first deploy, nothing to snapshot"
fi
REMOTE

say "Installing and restarting $SERVICE_UNIT"
if "${SSH[@]}" bash -seu <<REMOTE
mkdir -p '$LIVE'
# Code only. node_modules, data/ and .env already living in \$LIVE are preserved.
rsync -a --exclude node_modules --exclude data --exclude '.env' '$STAGE/' '$LIVE/'
cd '$LIVE'
npm ci --no-audit --no-fund
systemctl restart '$SERVICE_UNIT'
for i in \$(seq 1 20); do
	if curl -fsS --max-time 3 "http://127.0.0.1:$HEALTH_PORT/health" >/dev/null; then
		echo "health ok after \${i}s"
		exit 0
	fi
	sleep 1
done
echo "health check failed on 127.0.0.1:$HEALTH_PORT/health" >&2
systemctl --no-pager --lines=40 status '$SERVICE_UNIT' >&2 || true
exit 1
REMOTE
then
	say "Deployed $SERVICE ✔  (rollback snapshot: $SNAPSHOT)"
else
	say "Deploy FAILED — rolling back to $SNAPSHOT"
	"${SSH[@]}" bash -seu <<REMOTE
if [ -d '$SNAPSHOT' ]; then
	rsync -a --exclude node_modules '$SNAPSHOT/' '$LIVE/'
	cd '$LIVE' && npm ci --no-audit --no-fund
	systemctl restart '$SERVICE_UNIT'
	echo "rolled back to $SNAPSHOT"
else
	echo "no snapshot to roll back to; leaving $LIVE as-is" >&2
fi
REMOTE
	exit 1
fi
