#!/usr/bin/env python3
"""replay_log.py <sqlancer log> [out.sql] -- turns a log SQLancer's CUBRID provider wrote for a reported case into a
csql input: DROP TABLE/VIEW IF EXISTS for every class the log creates, the statements that set the database up, then
the queries the report names (the '-- ' lines after the '-- On the database set up ...' header), each run once.
Run it on a pool database no campaign is using:
    python3 scripts/cubrid/replay_log.py logs/cubrid/database3.log case.sql && csql -u dba sqlancer0 -i case.sql
csql prints the whole error message, where SQLancer keeps only its first line."""
import re
import sys

HEADER = '-- On the database set up by the statements above'


def main():
    log = open(sys.argv[1], errors='replace').read().split('\n')
    try:
        h = next(i for i, line in enumerate(log) if line.startswith(HEADER))
    except StopIteration:
        sys.exit('no "%s" line in %s' % (HEADER, sys.argv[1]))
    setup = [line for line in log[:h] if line.strip() and not line.startswith('--')
             and not line.startswith('\t') and not line.startswith('java.') and not line.startswith('Caused by')]
    # the DBMS message may run over several lines: the queries are the '-- ' lines that follow it
    rest = log[h + 1:]
    start = next((i for i, line in enumerate(rest) if line.startswith('-- ')), len(rest))
    queries = []
    for line in rest[start:]:
        if not line.startswith('-- '):
            break
        q = line[3:].strip()
        if q:
            queries.append(q if q.endswith(';') else q + ';')
    created = []
    for s in setup:
        m = re.match(r'CREATE\s+(TABLE|VIEW)\s+(\w+)', s, re.I)
        if m:
            created.append((m.group(1).upper(), m.group(2)))
    out = ['-- replay of %s' % sys.argv[1]]
    # views first: a view names its tables
    for kind, name in sorted(created, key=lambda c: c[0] != 'VIEW'):
        out.append('DROP %s IF EXISTS %s;' % (kind, name))
    out += setup
    out.append('-- the queries of the report')
    out += queries
    text = '\n'.join(out) + '\n'
    if len(sys.argv) > 2:
        open(sys.argv[2], 'w').write(text)
        print('%s: %d setup statements, %d queries' % (sys.argv[2], len(setup), len(queries)))
    else:
        sys.stdout.write(text)


if __name__ == '__main__':
    main()
