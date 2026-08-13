#!/usr/bin/env bash
# dev-cycle.sh - build / uninstall / install / verify loop for the Logger
# Control plugin. Run from the plugin source directory.
#
#   ./dev-cycle.sh            # build + uninstall + install + verify
#   ./dev-cycle.sh install    # skip the build, use the existing zip
#   ./dev-cycle.sh state      # dump the plugin's /state endpoint
#   ./dev-cycle.sh set <logger> <level> [ttlMinutes]
#   ./dev-cycle.sh off <id>   # remove one override
#   ./dev-cycle.sh clear      # remove every override
#   ./dev-cycle.sh status     # installed plugin list
#   ./dev-cycle.sh logs [n]   # tail sailpoint.log
#
# Requires bash + curl and an .env.local holding IIQ_URL / IIQ_USER /
# IIQ_PASSWORD (see .env.local.template).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env.local"
[[ -f "$ENV_FILE" ]] || { echo "ERROR: $ENV_FILE not found (copy .env.local.template)"; exit 1; }
set -a; source "$ENV_FILE"; set +a

PLUGIN_NAME="TurnOnLoggers"
ZIP_PATH="${SCRIPT_DIR}/${PLUGIN_NAME}.zip"
REST="${IIQ_URL}/plugin/rest/${PLUGIN_NAME}"

CURL=(curl -s -u "${IIQ_USER}:${IIQ_PASSWORD}" -H "X-XSRF-TOKEN: dev-cycle")

say() { echo "[dev-cycle] $*"; }
die() { echo "[dev-cycle] ERROR: $*" >&2; exit 1; }

plugin_id() {
    "${CURL[@]}" "${IIQ_URL}/rest/plugins" \
        | tr ',' '\n' \
        | awk -v name="\"$PLUGIN_NAME\"" '
            /"id":/   { id=$0 }
            /"name":/ && $0 ~ name { print id; exit }
          ' \
        | grep -oE '"[a-f0-9]+"' | head -1 | tr -d '"'
}

cmd_build() {
    say "Building..."
    cmd.exe //c ".\\build.bat" 2>&1 | tail -4
    [[ -f "$ZIP_PATH" ]] || die "build did not produce $ZIP_PATH"
}

cmd_uninstall() {
    local id; id="$(plugin_id || true)"
    if [[ -z "$id" ]]; then say "not installed, skipping uninstall"; return 0; fi
    say "Uninstalling (id=$id)..."
    local http
    http=$("${CURL[@]}" -X DELETE "${IIQ_URL}/rest/plugins/${id}" -w "%{http_code}" -o /tmp/tol-uninstall.out)
    [[ "$http" == "200" || "$http" == "204" ]] || { head -c 500 /tmp/tol-uninstall.out; die "uninstall HTTP $http"; }
}

cmd_install() {
    [[ -f "$ZIP_PATH" ]] || die "zip not found: $ZIP_PATH"
    say "Installing $(basename "$ZIP_PATH")..."
    local http
    http=$("${CURL[@]}" -X POST -F "file=@${ZIP_PATH}" -F "fileName=${PLUGIN_NAME}.zip" \
        "${IIQ_URL}/rest/plugins" -w "%{http_code}" -o /tmp/tol-install.out)
    [[ "$http" == "200" || "$http" == "201" ]] || { head -c 900 /tmp/tol-install.out; echo; die "install HTTP $http"; }
    say "Installed."
}

cmd_state() {
    "${CURL[@]}" "${REST}/state" -w "\n[http %{http_code}]\n"
}

cmd_set() {
    local logger="${1:?logger required}" level="${2:-DEBUG}" ttl="${3:-60}"
    say "Setting $logger=$level for ${ttl}m..."
    "${CURL[@]}" -X POST -H "Content-Type: application/json" \
        -d "{\"logger\":\"${logger}\",\"level\":\"${level}\",\"ttlMinutes\":${ttl},\"hosts\":[\"*\"],\"note\":\"set by dev-cycle\"}" \
        "${REST}/entries" -w "\n[http %{http_code}]\n"
}

cmd_off() {
    local id="${1:?entry id required}"
    "${CURL[@]}" -X DELETE "${REST}/entries/${id}" -w "\n[http %{http_code}]\n"
}

cmd_clear() {
    "${CURL[@]}" -X DELETE "${REST}/entries" -w "\n[http %{http_code}]\n"
}

cmd_sync() {
    "${CURL[@]}" -X POST "${REST}/sync" -w "\n[http %{http_code}]\n"
}

cmd_status() {
    "${CURL[@]}" "${IIQ_URL}/rest/plugins" | tr ',' '\n' | grep -E '"(id|name|version|disabled)"' | head -40
}

cmd_logs() {
    local n="${1:-60}"
    local f="${SAILPOINT_LOG:-/c/Program Files/Apache Software Foundation/apache-tomcat-9.0.117/logs/sailpoint.log}"
    [[ -f "$f" ]] || die "log not found: $f (set SAILPOINT_LOG)"
    tail -n "$n" "$f"
}

cmd_verify() {
    say "Verifying REST is up..."
    local http
    http=$("${CURL[@]}" "${REST}/state" -w "%{http_code}" -o /tmp/tol-state.out)
    [[ "$http" == "200" ]] || { head -c 900 /tmp/tol-state.out; echo; die "state HTTP $http"; }
    say "OK - $(head -c 200 /tmp/tol-state.out)..."
}

case "${1:-cycle}" in
    build)     cmd_build ;;
    uninstall) cmd_uninstall ;;
    install)   cmd_install; sleep 3; cmd_verify ;;
    state)     cmd_state ;;
    set)       shift; cmd_set "$@" ;;
    off)       shift; cmd_off "$@" ;;
    clear)     cmd_clear ;;
    sync)      cmd_sync ;;
    status)    cmd_status ;;
    logs)      shift; cmd_logs "$@" ;;
    verify)    cmd_verify ;;
    cycle|"")  cmd_build; cmd_uninstall; cmd_install; sleep 4; cmd_verify ;;
    *) echo "Unknown command: $1"; exit 2 ;;
esac
