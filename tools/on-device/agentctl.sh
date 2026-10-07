#!/system/bin/sh
# agentctl — agent-side client for the AgentAccessibilityService command channel.
#
# Talks to the app's local unix socket (files/agentctl.sock, mode 600). One JSON
# command per invocation; response JSON on stdout. Same-uid only: filesystem
# permissions + peer-credential check reject other apps.
#
# Commands (canonical live index: agentctl commands; full trust picture:
#   agentctl access — filesystem probes, consent classes, session rules).
# Consent classes: UNGATED (no consent), GRANT-IS-CONSENT (health/location
# permission grant IS consent), SESSION-GATED (needs approved overlay),
# CLIENT-LOCAL (runs here in the shell — am/pm/keyevent/notify).
#   agentctl ping
#   agentctl tap <x> <y>            (BLOCKED on One UI 7 — gesture filter; use click)
#   agentctl longpress <x> <y>      (BLOCKED on One UI 7 — use click + a11y)
#   agentctl swipe ...              (BLOCKED on One UI 7)
#   agentctl click "label"          (node-click by text/desc — Samsung-proof input)
#   agentctl clickId view-id        (node-click by resource id)
#   agentctl text "hello world"     (types into the focused field)
#   agentctl back | home | notifications
#   agentctl keyevent <keycode>     (needs INJECT_EVENTS — often denied; prefer back/home)
#   agentctl screen                 (visible text + bounds JSON)
#   agentctl tree [maxdepth]        (active window hierarchy JSON)
#   agentctl launch <intent-uri>    (ACTION_VIEW — WORKS where shell am start is blocked)
#   agentctl am <args...>           (passthrough to am; Samsung blocks some targets)
#   agentctl notify "title" "text"  (app's own notification — always allowed)
#   agentctl health status|hr|steps [hours]|sleep [days]
#                                    (health telemetry; Health Connect grant IS consent —
#                                     grant via app > Telemetry; hr = newest synced sample)
#   agentctl location               (last-known/one-shot fix; location grant IS consent)
#   agentctl notifstatus            (notification-listener grant state, no content)
#   agentctl notiflist              (active notifications pkg/title/text — SESSION-GATED)
#   agentctl logtail [bytes]        (last N bytes of the app's own server.log, default 4096, max 16384 — NOT session-gated: app's own lifecycle lines, no user content)
#
# CONSENT-GATED: screen/tree/click/text/launch need an approved session first:
#   agentctl session "what you want to do" [seconds]   -> full-screen Approve/Deny overlay
# Requires: Settings > Accessibility > Agents Beamdown Agent = ON (a11y cmds).
# am/notify/keyevent work WITHOUT accessibility.

set -u
AGENT_NAME="${AGENT_NAME:-Letta agent}"
SOCK="${AGENTCTL_SOCK:-/data/data/com.angussoftware.letta.env/files/agentctl.sock}"

send() {  # send() <<< one line of JSON (unix socket, app-uid only)
    printf '%s\n' "$1" | /system/bin/toybox nc -U "$SOCK" 2>/dev/null
}

case "${1:-help}" in
    ping)      send '{"cmd":"ping"}' ;;
    tap)       send "{\"cmd\":\"tap\",\"x\":$2,\"y\":$3}" ;;
    longpress) send "{\"cmd\":\"longPress\",\"x\":$2,\"y\":$3}" ;;
    swipe)     send "{\"cmd\":\"swipe\",\"fromX\":$2,\"fromY\":$3,\"toX\":$4,\"toY\":$5,\"duration\":${6:-300}}" ;;
    text)      send "{\"cmd\":\"text\",\"value\":\"$(printf '%s' "$2" | sed 's/"/\\"/g')\"}" ;;
    back)      send '{"cmd":"back"}' ;;
    home)      send '{"cmd":"home"}' ;;
    notifications) send '{"cmd":"notifications"}' ;;
    click)     V=$(printf '%s' "$2" | sed 's/"/\\"/g'); send '{"cmd":"click","text":"'"$V"'"}' ;;
    clickId)   send "{\"cmd\":\"clickId\",\"id\":\"$2\"}" ;;
    screen)    send '{"cmd":"screenshot-text"}' ;;
    tree)      send "{\"cmd\":\"tree\",\"maxDepth\":${2:-18}}" ;;
    launch)    send "{\"cmd\":\"launch\",\"uri\":\"$2\"}" ;;
    status)    send '{"cmd":"status"}' ;;
    commands)  send '{"cmd":"commands"}' ;;
    capabilities) send '{"cmd":"capabilities"}' ;;
    access)    send '{"cmd":"access"}' ;;
    health)
        SUB="${2:-status}"
        case "$SUB" in
            status) send '{"cmd":"health","sub":"status"}' ;;
            hr)     send '{"cmd":"health","sub":"hr"}' ;;
            steps)  send "{\"cmd\":\"health\",\"sub\":\"steps\",\"hours\":${3:-24}}" ;;
            sleep)  send "{\"cmd\":\"health\",\"sub\":\"sleep\",\"days\":${3:-2}}" ;;
            *)      echo '{"ok":false,"error":"usage: health status|hr|steps [hours]|sleep [days]"}' ;;
        esac
        ;;
    location)   send '{"cmd":"location"}' ;;
    hcrequest)  send '{"cmd":"hcrequest"}' ;;
    notifstatus) send '{"cmd":"notifstatus"}' ;;
    notiflist)  send '{"cmd":"notiflist"}' ;;
    logtail)    send "{\"cmd\":\"logtail\",\"bytes\":${2:-4096}}" ;;
    session)
        SECS="${3:-300}"
        DESC=$(printf '%s' "$2" | sed 's/"/\\"/g')
        send "{\"cmd\":\"session\",\"desc\":\"$DESC\",\"agent\":\"$AGENT_NAME\",\"seconds\":$SECS}"
        ;;
    keyevent)  /system/bin/input keyevent "$2" ;;
    am)        shift; /system/bin/am "$@" ;;
    pm)        shift; /system/bin/pm "$@" ;;
    notify)
        # App's own notification via its status channel — no special permission.
        # Implementation: the LettaEnvironmentService listens for this broadcast.
        /system/bin/am broadcast -a com.angussoftware.letta.env.AGENT_NOTIFY \
            --es title "$2" --es text "$3" >/dev/null 2>&1 && \
            echo '{"ok":true,"via":"broadcast"}'
        ;;
    help|*)
        awk 'NR>1 && /^$/{exit} NR>1 && /^#/{sub(/^# ?/,""); print}' "$0"
        ;;
esac
