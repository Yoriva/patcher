#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -uo pipefail

usage() {
    cat <<EOF
Usage: $0 <repo> <runid> <interval>

  repo      GitHub repository, e.g. Yoriva/patcher
  runid     GitHub Actions run id to monitor
  interval  Poll interval in seconds, e.g. 3

Example:
  $0 Yoriva/patcher 43918880107 3
EOF
}

if [ "$#" -ne 3 ]; then
    usage
    exit 64
fi

REPO="$1"
RUN_ID="$2"
INTERVAL="$3"

case "$REPO" in
    */*) ;;
    *)
        printf 'Invalid repo: %s (expected owner/repo)\n' "$REPO" >&2
        exit 64
        ;;
esac

case "$RUN_ID" in
    ''|*[!0-9]*)
        printf 'Invalid runid: %s\n' "$RUN_ID" >&2
        exit 64
        ;;
esac

case "$INTERVAL" in
    ''|*[!0-9]*|0)
        printf 'Invalid interval: %s (must be a positive number of seconds)\n' "$INTERVAL" >&2
        exit 64
        ;;
esac

for cmd in gh jq; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$cmd" >&2
        exit 69
    fi
done

if ! gh auth status >/dev/null 2>&1; then
    printf 'GitHub CLI session missing. Run `gh auth login` first.\n' >&2
    exit 77
fi

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET=$'\033[0m'
    C_BOLD=$'\033[1m'
    C_DIM=$'\033[2m'
    C_RED=$'\033[31m'
    C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[34m'
    C_MAGENTA=$'\033[35m'
    C_CYAN=$'\033[36m'
else
    C_RESET=''
    C_BOLD=''
    C_DIM=''
    C_RED=''
    C_GREEN=''
    C_YELLOW=''
    C_BLUE=''
    C_MAGENTA=''
    C_CYAN=''
fi

declare -A SEEN=()
emit() {
    local key="$1"
    shift
    if [ -n "${SEEN[$key]:-}" ]; then
        return 0
    fi
    SEEN["$key"]=1
    printf '%s\n' "$*"
}

api() {
    gh api -H 'Accept: application/vnd.github+json' "$@"
}

symbol_for_conclusion() {
    case "$1" in
        success) printf '✅' ;;
        failure) printf '❌' ;;
        cancelled) printf '⛔' ;;
        skipped) printf '⚪' ;;
        timed_out) printf '⏰' ;;
        *) printf '•' ;;
    esac
}

job_label() {
    case "$1" in
        queued) printf 'queued' ;;
        in_progress) printf 'running' ;;
        completed) printf 'finished' ;;
        waiting) printf 'waiting' ;;
        *) printf '%s' "$1" ;;
    esac
}

trap 'printf "\n⛔ Monitor stopped.\n"; exit 130' INT TERM

printf '\n%s╭────────────────────────────────────────────────────────────╮%s\n' "$C_CYAN" "$C_RESET"
printf '%s│%s %s%s GitHub Actions monitor%s                              %s│%s\n' \
    "$C_CYAN" "$C_RESET" "$C_BOLD" "🚀" "$C_RESET" "$C_CYAN" "$C_RESET"
printf '%s│%s 📦 %s%s/%s%s\n' "$C_CYAN" "$C_RESET" "$C_BOLD" "$REPO" "$RUN_ID" "$C_RESET"
printf '%s│%s ⏱️  Polling every %s seconds; each update is printed only once.%s\n' \
    "$C_CYAN" "$C_RESET" "$INTERVAL" "$C_RESET"
printf '%s╰────────────────────────────────────────────────────────────╯%s\n\n' "$C_CYAN" "$C_RESET"

error_count=0
run_name=''
run_url=''

while :; do
    if ! run_json=$(api "repos/$REPO/actions/runs/$RUN_ID" 2>&1); then
        error_count=$((error_count + 1))
        emit "api-error:$error_count" "⚠️  GitHub API error ($error_count/5): $run_json"
        if [ "$error_count" -ge 5 ]; then
            printf '\n❌ GitHub API unreachable, stopping monitor.\n' >&2
            exit 1
        fi
        sleep "$INTERVAL"
        continue
    fi

    if ! printf '%s' "$run_json" | jq -e . >/dev/null 2>&1; then
        emit 'invalid-json' '⚠️  GitHub returned an unexpected response, retrying.'
        sleep "$INTERVAL"
        continue
    fi

    error_count=0
    run_name=$(printf '%s' "$run_json" | jq -r '.name // .workflow_name // "workflow"')
    run_status=$(printf '%s' "$run_json" | jq -r '.status // "unknown"')
    run_conclusion=$(printf '%s' "$run_json" | jq -r '.conclusion // ""')
    run_url=$(printf '%s' "$run_json" | jq -r '.html_url // ""')
    run_branch=$(printf '%s' "$run_json" | jq -r '.head_branch // "-"')
    run_sha=$(printf '%s' "$run_json" | jq -r '.head_sha // "-"')

    if [ -z "${SEEN[header:$RUN_ID]:-}" ]; then
        emit "header:$RUN_ID" "🎬 ${C_BOLD}${run_name}${C_RESET} • ${run_branch} • ${run_sha:0:8}"
        if [ -n "$run_url" ]; then
            emit "url:$RUN_ID" "🔗 ${C_BLUE}${run_url}${C_RESET}"
        fi
    fi

    case "$run_status" in
        queued)
            emit "run:$RUN_ID:status:queued" "⏳ Run queued."
            ;;
        in_progress)
            emit "run:$RUN_ID:status:in_progress" "🟡 Run in progress."
            ;;
        completed)
            case "$run_conclusion" in
                success) icon='🎉' ;;
                failure) icon='💥' ;;
                cancelled) icon='⛔' ;;
                timed_out) icon='⏰' ;;
                *) icon='🏁' ;;
            esac
            emit "run:$RUN_ID:status:completed" "$icon Run finished: ${run_conclusion:-unknown}"
            ;;
        *)
            emit "run:$RUN_ID:status:$run_status" "ℹ️  Run status: $run_status"
            ;;
    esac

    jobs_json=''
    if ! jobs_json=$(api "repos/$REPO/actions/runs/$RUN_ID/jobs?per_page=100" 2>/dev/null); then
        emit 'jobs-error' '⚠️  Job data unavailable, retrying next poll.'
    elif printf '%s' "$jobs_json" | jq -e . >/dev/null 2>&1; then
        while IFS=$'\t' read -r kind a b c d e; do
            case "$kind" in
                J)
                    job_id="$a"
                    job_name="$b"
                    job_status="$c"
                    job_conclusion="$d"
                    if [ "$job_status" = 'completed' ]; then
                        job_icon=$(symbol_for_conclusion "$job_conclusion")
                        emit "job:$job_id:status:completed" \
                            "   ${job_icon} ${job_name} — finished${job_conclusion:+ ($job_conclusion)}"
                    else
                        case "$job_status" in
                            in_progress) job_icon='🔧' ;;
                            queued) job_icon='⏳' ;;
                            waiting) job_icon='⌛' ;;
                            *) job_icon='•' ;;
                        esac
                        emit "job:$job_id:status:$job_status" \
                            "   ${job_icon} ${job_name} — $(job_label "$job_status")"
                    fi
                    ;;
                S)
                    step_job="$a"
                    step_number="$b"
                    step_name="$c"
                    step_status="$d"
                    step_conclusion="$e"
                    case "$step_status" in
                        in_progress)
                            emit "step:$step_job:$step_number:in_progress" \
                                "      ${C_DIM}🔧 ${step_name}${C_RESET}"
                            ;;
                        completed)
                            step_icon=$(symbol_for_conclusion "$step_conclusion")
                            emit "step:$step_job:$step_number:completed" \
                                "      ${C_DIM}${step_icon} ${step_name}${C_RESET}"
                            ;;
                    esac
                    ;;
            esac
        done < <(
            printf '%s' "$jobs_json" | jq -r '
                .jobs[] as $j |
                ("J\t\($j.id)\t\($j.name)\t\($j.status)\t\($j.conclusion // "")"),
                ($j.steps[]? | "S\t\($j.id)\t\(.number)\t\(.name)\t\(.status)\t\(.conclusion // "")")
            '
        )
    fi

    if [ "$run_status" = 'completed' ]; then
        break
    fi

    sleep "$INTERVAL"
done

if [ "$run_conclusion" = 'success' ]; then
    printf '\n%s╭────────────────────────────────────────────────────────────╮%s\n' "$C_GREEN" "$C_RESET"
    printf '%s│%s 🎉 %sAll jobs completed successfully.%s                      %s│%s\n' \
        "$C_GREEN" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_GREEN" "$C_RESET"
    printf '%s│%s ✅ Build succeeded, shutting down monitor.%s               %s│%s\n' \
        "$C_GREEN" "$C_RESET" "$C_RESET" "$C_GREEN" "$C_RESET"
    printf '%s╰────────────────────────────────────────────────────────────╯%s\n\n' "$C_GREEN" "$C_RESET"
    exit 0
fi

printf '\n%s╭────────────────────────────────────────────────────────────╮%s\n' "$C_RED" "$C_RESET"
printf '%s│%s 💥 %sBuild failed: %s%s\n' \
    "$C_RED" "$C_RESET" "$C_BOLD" "$run_conclusion" "$C_RESET"
printf '%s╰────────────────────────────────────────────────────────────╯%s\n\n' "$C_RED" "$C_RESET"

log_file="${TMPDIR:-/tmp}/yoriva-action-${RUN_ID}-error.log"
printf '📜 Fetching error log...\n\n'

if ! gh run view "$RUN_ID" --repo "$REPO" --log-failed >"$log_file" 2>/dev/null || [ ! -s "$log_file" ]; then
    gh run view "$RUN_ID" --repo "$REPO" --log >"$log_file" 2>/dev/null || true
fi

printf '%s────────────── ERROR LOG ──────────────%s\n' "$C_RED" "$C_RESET"
if [ -s "$log_file" ]; then
    highlights=$(grep -inE 'error|failed|failure|fatal|##\[error\]' "$log_file" || true)
    if [ -n "$highlights" ]; then
        printf '%s\n' "$highlights"
    else
        tail -n 200 "$log_file"
    fi
    printf '\n📄 Full log: %s\n' "$log_file"
else
    printf '⚠️  Could not fetch the error log.\n'
fi

printf '%s───────────────────────────────────────%s\n\n' "$C_RED" "$C_RESET"

exit 1
