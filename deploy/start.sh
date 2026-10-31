#!/bin/sh
set -e

# Railway's Postgres hands out postgresql://USER:PASSWORD@HOST:PORT/DB. The JDBC driver wants the
# address and the login separately, so split it here. A jdbc: URL is passed through untouched.
case "${DATABASE_URL:-}" in
  postgres://*|postgresql://*)
    rest=${DATABASE_URL#*://}
    login=${rest%%@*}
    export DATABASE_USER="${login%%:*}"
    export DATABASE_PASSWORD="${login#*:}"
    export DATABASE_URL="jdbc:postgresql://${rest#*@}"
    ;;
esac

exec java $JAVA_OPTS -jar /app/app.jar
