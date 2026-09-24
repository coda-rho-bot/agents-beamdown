#!/system/bin/sh
# agentctl — agent-side client for the AgentAccessibilityService command channel.
#
# Talks to the app's local TCP socket (127.0.0.1:8765). One JSON command per
# invocation; response JSON on stdout. Same-uid only: the socket rejects other apps.
#
# Commands (see AgentAccessibilityService.handle):
#   agentctl ping
#   agentctl tap <x> <y>
#   agentctl longpress <x> <y>
#   agentctl swipe <fx> <fy> <tx> <ty> [duration_ms]
#   agentctl text "hello world"      (types into the focused field)
#   agentctl back | home | notifications
#   agentctl keyevent <keycode>      (arbitrary keys via input keyevent)
#   agentctl screen                  (visible text + bounds JSON)
#   agentctl tree [maxdepth]         (active window hierarchy JSON)
#   agentctl launch <intent-uri>     (ACTION_VIEW)
#   agentctl am <args...>            (passthrough to am; e.g. start -n pkg/.Act)
#   agentctl notify "title" "text"   (app's own notification — always allowed)
#
# Requires: Settings > Accessibility > Letta Environment Agent = ON (a11y cmds).
# am/notify/keyevent work WITHOUT accessibility.

set -u
PORT=8765
HOST=127.0.0.1

send() {  # send() <<< one line of JSON
    printf '%s\n' "$1" | /system/bin/toybox nc "$HOST" "$PORT" 2>/dev/null
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
    screen)    send '{"cmd":"screenshot-text"}' ;;
    tree)      send "{\"cmd\":\"tree\",\"maxDepth\":${2:-18}}" ;;
    launch)    send "{\"cmd\":\"launch\",\"uri\":\"$2\"}" ;;
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
        sed -n '2,25p' "$0" | grep -E '^#' | sed 's/^# \{0,1\}//'
        ;;
esac
