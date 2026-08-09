#!/usr/bin/env bash
#
# Push a rotated NTA API key to the box that runs gtfsr-collector.
#
# This exists because deploy-backend.sh deliberately never syncs .env: code and secrets travel
# by different routes, so a routine code deploy can never clobber (or leak) a key. Rotation is
# the one case that has to touch .env, so it gets its own script.
#
# gtfsr-collector is the ONLY service that holds the NTA key -- it polls the feed and archives
# snapshots that gtfsr-stop-times then reads. Nothing else needs updating.
#
# Usage:
#   DEPLOY_HOST=hetzner scripts/rotate-nta-key.sh
#
# The new key is read from a prompt, never from argv: an argument would land in shell history
# and in the process table where any other user on the box can read it.
#
# Note on the defaults: on hetzner the services are NOT root system units. They run as systemd
# *user* units under the `lab` account (lingering enabled), out of ~lab/Projects/busbet. So we ssh
# in as root and drive the unit with `--machine=lab@.host --user`. Set SERVICE_ACCOUNT="" if you
# ever move the collector to a real system unit.
#
# Optional environment:
#   DEPLOY_USER      ssh user                      (default: root)
#   DEPLOY_PATH      dir holding the service dirs  (default: /home/lab/Projects/busbet)
#   SERVICE_ACCOUNT  user whose systemd runs it    (default: lab; "" for a system unit)
#   SERVICE_UNIT     systemd unit name             (default: gtfsr-collector.service)
#   SSH_OPTS         extra ssh options
#   DRY_RUN=1        print what would happen; touch nothing remote
#   SKIP_LOCAL=1     don't touch the local .env, only the remote one
#
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-}"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_PATH="${DEPLOY_PATH:-/home/lab/Projects/busbet}"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT-lab}"
SERVICE_UNIT="${SERVICE_UNIT:-gtfsr-collector.service}"
SSH_OPTS="${SSH_OPTS:-}"
DRY_RUN="${DRY_RUN:-0}"
SKIP_LOCAL="${SKIP_LOCAL:-0}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ENV="$REPO_ROOT/gtfsr-collector/.env"

die() { echo "rotate-nta-key: $*" >&2; exit 1; }

[[ -n "$DEPLOY_HOST" ]] || die "DEPLOY_HOST is required (no default, on purpose)"
[[ -n "$DEPLOY_PATH" ]] || die "DEPLOY_PATH is required (parent dir holding gtfsr-collector/)"

TARGET="$DEPLOY_USER@$DEPLOY_HOST"
REMOTE_ENV="$DEPLOY_PATH/gtfsr-collector/.env"

# systemctl/journalctl prefix. A user unit under another account is reachable from root only via
# --machine=<user>@.host --user; plain `systemctl restart` would silently look for a system unit
# of the same name and fail with "not found".
if [[ -n "$SERVICE_ACCOUNT" ]]; then
	SYSTEMCTL="systemctl --machine=$SERVICE_ACCOUNT@.host --user"
	JOURNALCTL="journalctl --machine=$SERVICE_ACCOUNT@.host --user"
else
	SYSTEMCTL="systemctl"
	JOURNALCTL="journalctl"
fi

# ── Read the new key ────────────────────────────────────────────────────────
# -s so it never echoes to a shared screen or a scrollback buffer.
# `|| die` rather than bare `read`: under `set -e` a read from a closed stdin (cron, </dev/null)
# aborts the script with no message at all, which looks like a hang.
read -rsp "New NTA_API_KEY: " NEW_KEY || die "needs an interactive terminal to read the key"
echo
[[ -n "$NEW_KEY" ]] || die "empty key, nothing to do"
read -rsp "Confirm: " CONFIRM_KEY || die "needs an interactive terminal to read the key"
echo
[[ "$NEW_KEY" == "$CONFIRM_KEY" ]] || die "keys don't match"

# A typo'd key deploys cleanly and then silently starves the feed, so check it against the real
# endpoint before writing it anywhere.
echo "Validating key against the NTA feed…"
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
	-H "x-api-key: $NEW_KEY" \
	"https://api.nationaltransport.ie/gtfsr/v2/TripUpdates?format=json" || echo "000")
case "$HTTP_CODE" in
	200) echo "  key accepted (HTTP 200)" ;;
	401|403) die "NTA rejected the key (HTTP $HTTP_CODE) -- not deploying it" ;;
	000) die "couldn't reach the NTA API to validate -- check connectivity" ;;
	*)   die "unexpected response from NTA (HTTP $HTTP_CODE) -- not deploying" ;;
esac

if [[ "$DRY_RUN" == "1" ]]; then
	echo "DRY_RUN: would update $LOCAL_ENV"
	echo "DRY_RUN: would update $TARGET:$REMOTE_ENV"
	echo "DRY_RUN: would restart $SERVICE_UNIT on $TARGET"
	exit 0
fi

# ── Local .env ──────────────────────────────────────────────────────────────
if [[ "$SKIP_LOCAL" != "1" && -f "$LOCAL_ENV" ]]; then
	cp -p "$LOCAL_ENV" "$LOCAL_ENV.bak"
	# Match only the assignment at line start so a commented example line is left alone.
	if grep -q '^NTA_API_KEY=' "$LOCAL_ENV"; then
		# In-place via a temp file rather than sed -i: the key can contain / and & which sed
		# would treat as delimiters and replacement syntax.
		python3 - "$LOCAL_ENV" "$NEW_KEY" <<-'PY'
			import sys
			path, key = sys.argv[1], sys.argv[2]
			lines = open(path).read().splitlines(keepends=True)
			out = ["NTA_API_KEY=" + key + "\n" if l.startswith("NTA_API_KEY=") else l for l in lines]
			open(path, "w").writelines(out)
		PY
	else
		printf 'NTA_API_KEY=%s\n' "$NEW_KEY" >> "$LOCAL_ENV"
	fi
	echo "Updated $LOCAL_ENV (previous kept at $LOCAL_ENV.bak)"
fi

# ── Remote .env ─────────────────────────────────────────────────────────────
# The key goes over stdin, not in the command string, so it stays out of the remote process
# table and out of any command auditing on the box.
echo "Updating $TARGET:$REMOTE_ENV…"
printf '%s' "$NEW_KEY" | ssh $SSH_OPTS "$TARGET" bash -s -- "$REMOTE_ENV" <<-'REMOTE'
	set -euo pipefail
	REMOTE_ENV="$1"
	NEW_KEY="$(cat)"

	[[ -f "$REMOTE_ENV" ]] || { echo "no .env at $REMOTE_ENV" >&2; exit 1; }

	cp -p "$REMOTE_ENV" "$REMOTE_ENV.bak"
	python3 - "$REMOTE_ENV" "$NEW_KEY" <<-'PY'
		import sys
		path, key = sys.argv[1], sys.argv[2]
		lines = open(path).read().splitlines(keepends=True)
		found = any(l.startswith("NTA_API_KEY=") for l in lines)
		out = ["NTA_API_KEY=" + key + "\n" if l.startswith("NTA_API_KEY=") else l for l in lines]
		if not found:
		    out.append("NTA_API_KEY=" + key + "\n")
		open(path, "w").writelines(out)
	PY
	chmod 600 "$REMOTE_ENV"
	echo "  .env updated (previous kept at $REMOTE_ENV.bak)"
REMOTE

# ── Restart and verify ──────────────────────────────────────────────────────
echo "Restarting $SERVICE_UNIT…"
ssh $SSH_OPTS "$TARGET" "$SYSTEMCTL restart '$SERVICE_UNIT'"

# The collector polls on an interval, so give it a beat to make its first request before judging.
sleep 15

echo "Recent unit log:"
ssh $SSH_OPTS "$TARGET" "$JOURNALCTL -u '$SERVICE_UNIT' --since '1 min ago' --no-pager | tail -20"

if ssh $SSH_OPTS "$TARGET" "$SYSTEMCTL is-active --quiet '$SERVICE_UNIT'"; then
	echo
	echo "$SERVICE_UNIT is active. Check the log above shows a successful poll, not 401s."
	echo "Once you're satisfied, remove the backups:"
	echo "  ssh $TARGET 'rm $REMOTE_ENV.bak'"
	echo "  rm $LOCAL_ENV.bak"
	echo "Then revoke the old key in the NTA developer portal."
else
	die "$SERVICE_UNIT is NOT active after restart -- restore with: ssh $TARGET \"mv $REMOTE_ENV.bak $REMOTE_ENV && $SYSTEMCTL restart $SERVICE_UNIT\""
fi
