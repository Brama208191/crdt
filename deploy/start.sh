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

# On Railway a missing DATABASE_URL would otherwise surface as a long "localhost:5432 refused" trace.
if [ -z "${DATABASE_URL:-}" ] && [ -n "${RAILWAY_ENVIRONMENT_NAME:-}" ]; then
  echo "start: DATABASE_URL is not set. Add it on this service as \${{Postgres.DATABASE_URL}}, then deploy." >&2
  exit 1
fi
echo "start: database ${DATABASE_URL:-jdbc:postgresql://localhost:5432/crdt (default)}"

exec java $JAVA_OPTS -jar /app/app.jar
