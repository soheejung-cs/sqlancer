#!/bin/bash
# sqlancer-cubrid-pool.sh <create|start|stop|status|drop> [N=1] -- the CUBRID databases SQLancer's CUBRID provider
# uses, one per worker thread: <prefix>0 .. <prefix><N-1>. CUBRID cannot create a database from SQL, so the provider
# keeps these and drops their user tables and views before each database it generates: give it databases of its own.
#
# Needs a CUBRID environment ($CUBRID set, cubrid and csql on PATH) and a running broker (default port 33000).
#   SQLANCER_CUBRID_DB_ROOT  where the volumes go          (default: ./cubrid-pool)
#   SQLANCER_CUBRID_PREFIX   database name prefix          (default: sqlancer — the provider's --database-pool-prefix)
#   SQLANCER_CUBRID_LOCALE   locale given to createdb      (default: en_US.utf8)
# Each database gets a [@<db>] section in $CUBRID/conf/cubrid.conf with small buffers, so that N servers stay small
# whatever [common] says. Servers are started into a log file, never a pipe (the daemon would hold the pipe open).
set -u
cmd=${1:?usage: sqlancer-cubrid-pool.sh <create|start|stop|status|drop> [N]}
n=${2:-1}
: "${CUBRID:?set CUBRID to the CUBRID installation}"
mkdir -p "${SQLANCER_CUBRID_DB_ROOT:-./cubrid-pool}"
root=$(cd "${SQLANCER_CUBRID_DB_ROOT:-./cubrid-pool}" && pwd)
prefix=${SQLANCER_CUBRID_PREFIX:-sqlancer}
locale=${SQLANCER_CUBRID_LOCALE:-en_US.utf8}
conf=$CUBRID/conf/cubrid.conf
dbtxt=${CUBRID_DATABASES:-$CUBRID/databases}/databases.txt

for ((i = 0; i < n; i++)); do
  db=$prefix$i
  case $cmd in
    create)
      if [ -f "$dbtxt" ] && grep -q "^$db[[:space:]]" "$dbtxt"; then
        echo "$db: already in $dbtxt"
        continue
      fi
      mkdir -p "$root/$db"
      # createdb leaves its error log in the current directory: keep it next to the volumes
      (cd "$root/$db" && cubrid createdb --db-volume-size=64M --log-volume-size=64M \
        -F "$root/$db" -L "$root/$db" "$db" "$locale") > "$root/$db.create.log" 2>&1 \
        || { cat "$root/$db.create.log"; exit 1; }
      grep -q "^\[@$db\]" "$conf" \
        || printf '\n[@%s]\ndata_buffer_size=128M\nlog_buffer_size=16M\n' "$db" >> "$conf"
      echo "$db: created in $root/$db"
      ;;
    start)
      cubrid server start "$db" > "$root/$db.start.log" 2>&1
      echo "$db: $(grep -E 'success|fail|already|running|error' -i "$root/$db.start.log" | tail -1)"
      ;;
    stop)
      cubrid server stop "$db" > "$root/$db.stop.log" 2>&1
      echo "$db: $(grep -E 'success|fail|not running|error' -i "$root/$db.stop.log" | tail -1)"
      ;;
    status)
      cubrid server status 2>&1 | grep -w "$db" || echo "$db: not running"
      ;;
    drop)
      cubrid server stop "$db" > /dev/null 2>&1
      cubrid deletedb "$db" && rm -rf "${root:?}/$db" "$root/$db".*.log
      echo "$db: dropped (the [@$db] section of cubrid.conf is left as it is)"
      ;;
    *)
      echo "usage: sqlancer-cubrid-pool.sh <create|start|stop|status|drop> [N]"
      exit 1
      ;;
  esac
done
