#!/bin/bash
# sqlancer-cubrid-watchdog.sh <run dir> [N=4] -- keeps the SQLancer pool servers <prefix>0..N-1 up while the campaign whose pid is
# in <run dir>/run.pid runs, and records every death for triage: on a debug build an assert takes a server down, and the
# campaign would otherwise only see connection errors.
#   - a pool server that is gone: the tail of its error log and the new cores (in the directory of
#     kernel.core_pattern, or CORE_DIR) go to <run dir>/watchdog.log, then the server is started again if the master
#     has not done so by itself (auto_restart_server) — into a file, never a pipe: the daemon would hold it open
#   - a CAS whose pid changed (the broker restarts a dead CAS by itself): the tail of its error log is recorded
#   - at most MAX_RESTARTS server restarts a day, so that a crash on every query cannot fill the disk with cores
# Ends when the campaign process is gone.
set -u
run=$(cd "${1:?usage: sqlancer-cubrid-watchdog.sh <run dir> [N]}" && pwd)
n=${2:-4}
prefix=${SQLANCER_CUBRID_PREFIX:-sqlancer}
interval=${WATCHDOG_INTERVAL:-30}
max_restarts=${MAX_RESTARTS:-30}
log=$run/watchdog.log
core_dir=${CORE_DIR:-$(pattern=$(cat /proc/sys/kernel/core_pattern 2>/dev/null); case $pattern in /*) dirname "$pattern" ;; *) echo "" ;; esac)}
restarts=0
day=$(date +%F)
declare -A cas_pid

note () { echo "[$(date '+%F %T')] $*" >> "$log"; }

note "watchdog start: run=$run servers=${prefix}0..$((n - 1)) interval=${interval}s max_restarts/day=$max_restarts CUBRID=$(readlink -f "$CUBRID")"
last_core_check=$(date +%s)
while kill -0 "$(cat "$run/run.pid" 2>/dev/null)" 2>/dev/null; do
  [ "$(date +%F)" != "$day" ] && { day=$(date +%F); restarts=0; }
  for ((i = 0; i < n; i++)); do
    db=$prefix$i
    pgrep -x cub_server -a | awk '{print $3}' | grep -qx "$db" && continue
    errlog=$(ls -t "$CUBRID"/log/server/"${db}"_*.err 2>/dev/null | head -1)
    note "SERVER DOWN: $db (error log ${errlog:-none})"
    [ -n "$errlog" ] && tail -40 "$errlog" | sed 's/^/    | /' >> "$log"
    [ -n "$core_dir" ] && find "$core_dir" -maxdepth 1 -name 'core.*' -newermt "@$last_core_check" -printf '    core: %f %s bytes\n' 2>/dev/null >> "$log"
    if [ "$restarts" -ge "$max_restarts" ]; then
      note "restart limit ($max_restarts/day) reached: $db stays down"
      continue
    fi
    restarts=$((restarts + 1))
    cubrid server start "$db" > "$run/restart-$db.log" 2>&1
    note "restarted $db ($restarts today): $(grep -Ei 'success|fail|error' "$run/restart-$db.log" | tail -1)"
  done
  while read -r pid name; do
    [ -z "$name" ] && continue
    if [ -n "${cas_pid[$name]:-}" ] && [ "${cas_pid[$name]}" != "$pid" ]; then
      id=${name##*_}
      note "CAS RESTARTED: $name pid ${cas_pid[$name]} -> $pid"
      tail -15 "$CUBRID/log/broker/error_log/broker1_${id}.err" 2>/dev/null | sed 's/^/    | /' >> "$log"
      [ -n "$core_dir" ] && find "$core_dir" -maxdepth 1 -name 'core.*' -newermt "@$last_core_check" -printf '    core: %f %s bytes\n' 2>/dev/null >> "$log"
    fi
    cas_pid[$name]=$pid
  done < <(ps -eo pid=,args= | awk '$2 ~ /_cub_cas_[0-9]+$/ {print $1, $2}')
  last_core_check=$(date +%s)
  sleep "$interval"
done
note "watchdog end: campaign process gone"
