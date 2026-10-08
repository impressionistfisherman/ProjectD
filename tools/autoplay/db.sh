#!/bin/sh
# Usage: DALMARU_DB_PASSWORD=... ./db.sh "SQL"  — read-only evidence queries.
PGPASSWORD="$DALMARU_DB_PASSWORD" "/c/Program Files/PostgreSQL/18/bin/psql.exe" -h localhost -p 15432 -U postgres -d ProjectD -P pager=off -c "$1"
