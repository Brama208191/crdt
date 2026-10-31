# Deployment

The editor runs on [Railway](https://railway.com) as two services: the app (one Docker container) and a Railway Postgres database. The app sleeps when nobody is using it, so it costs very little.

## Shape of the deployment

```mermaid
flowchart LR
  user([Browser tabs]) -- HTTPS + WebSocket --> edge[Railway edge]
  edge --> app
  subgraph svc [App service, one container]
    app[Spring Boot<br/>serves the React client, REST and STOMP on /ws]
  end
  app -- private network --> db[(Railway Postgres)]
  gh[GitHub push] --> ci[CI workflow] -- passes --> build[Railway build] --> svc
```

| Piece | Where it lives |
|---|---|
| Image | [`Dockerfile`](../Dockerfile): builds the client, bundles it into the Spring Boot jar, and runs it as a non-root user |
| Start script | [`deploy/start.sh`](../deploy/start.sh): turns Railway's `postgresql://` connection string into the JDBC settings, then starts Java |
| Railway settings | Set in the dashboard (steps below). [`railway.json`](../railway.json) holds the same build and health check settings for services that still read config files |
| Data | Railway Postgres. Flyway creates the tables on the first start |

Memory is capped at 256 MB of heap. Measured locally under a 512 MB container limit, the app uses about 0.3 GB with two editors connected.

## First deploy

1. In Railway, open your project (the Streamlet one is fine, or click **New Project** for a separate one). The bill is per workspace either way.
2. **Add the database:** click **Create** (or right-click the canvas), then **Database, PostgreSQL**. Railway creates a `Postgres` service with its own volume.
3. **Add the app:** click **Create, GitHub Repo**, and pick `Brama208191/crdt`. Allow Railway's GitHub app to access the repository if it asks. Railway finds the `Dockerfile` and starts building.
4. **Connect the app to the database.** Open the app service, go to **Variables**, click **New Variable**, and add:

   | Name | Value |
   |---|---|
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` |

   The `${{...}}` part is a reference: Railway fills in the database's private address and password, and keeps them up to date. Nothing secret ends up in the repository. No other variables are needed. Railway sets `PORT` itself.
5. In the app service's **Settings**:
   - **Deploy, Healthcheck Path**: `/actuator/health`. Railway then marks a deploy live only once the app has started and reached the database.
   - **Deploy, Serverless**: turn it on. The app sleeps after about 5 to 10 minutes without traffic, and wakes on the next visit.
   - **Deploy, Restart Policy**: On Failure.
   - **Source, Wait for CI**: turn it on, so a push deploys only after the CI workflow passes.
   - **Networking, Generate Domain**: you get `https://<name>.up.railway.app`. Put that link in the README's **Live demo** line.
6. Optionally, also turn on **Serverless** for the `Postgres` service. Once the app is asleep and its connections are closed, the database can sleep too, and the app wakes it on the next request.
7. Open the domain, create a document, open the same link in a second tab, and type in both.

## What it costs

On the Hobby plan ($5 a month, including $5 of usage), Railway charges $10 per GB of memory per month, $20 per vCPU per month, and $0.15 per GB of volume storage.

| Service | Usage | Approximate cost per month |
|---|---|---|
| App, awake about 10% of the time | ~0.3 GB while awake | $0.30 to $1 |
| Postgres, always on | ~0.1 GB, a small volume | $1 to $1.50 |
| Postgres, with Serverless on | mostly asleep | under $0.50 |

That is about $1 to $3 a month on top of other services in the same workspace. A usage limit under **Workspace settings, Usage** caps the worst case.

## Why the app can sleep

Railway puts a service to sleep only when it has sent no outbound traffic for a few minutes, and traffic to the database counts. So the connection pool closes idle connections after one minute (`spring.datasource.hikari.minimum-idle=0` in [`application.properties`](../server/src/main/resources/application.properties)), and nothing in the app runs on a timer. An open editor tab keeps its WebSocket alive, so the app stays awake while someone is editing.

**Waking up:** the first visit after a sleep starts the JVM, which takes a few seconds. That first request can briefly get a 502 from Railway's proxy. Reloading fixes it, and the client reconnects its WebSocket on its own.

## The admin password

The creator of a document can always delete it or clear its history from the browser that made it. To manage every document, including ones made before this feature, set up an admin password:

1. On your computer, in the repository folder, run:

   ```bash
   python deploy/admin-hash.py --generate
   ```

   It prints a new random password once (save it in a password manager) and a line starting with `ADMIN_KEY_HASH=pbkdf2-sha256:`. To pick your own password instead (16 characters or more), run it without `--generate`.
2. In Railway, open the app service, then **Variables, New Variable**. Name: `ADMIN_KEY_HASH`. Value: everything after the `=`. Deploy the change.
3. Open `https://<your domain>/#/admin` and sign in. The browser keeps you signed in for 30 days.

Only the hash is stored, in Railway; the app never stores or logs the password. If someone saw the variable, the 600,000 PBKDF2 rounds and a random salt make guessing a long password impractical. To change the password, or sign everyone out, make a new hash and replace the variable. Removing the variable turns admin sign-in off.

## Security headers

Every response carries a Content Security Policy (scripts only from the app itself, fonts from Google Fonts), HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, a referrer policy and a permissions policy. See [`SecurityHeadersFilter.java`](../server/src/main/java/dev/crdt/editor/SecurityHeadersFilter.java).

## Operating it

| Task | Where |
|---|---|
| Logs | App service, **Deployments**, then the latest deployment's logs |
| Roll back | **Deployments**, then **Redeploy** on an earlier deployment |
| Look at the data | `Postgres` service, **Data** tab |
| Delete or clean up any document | Sign in at `#/admin`, open the document, use **Delete** or **History, Clear** |
| Cost so far this month | Workspace, **Usage** |

## Trying the production image locally

```bash
docker network create crdt-net
docker run -d --name crdt-db --network crdt-net -e POSTGRES_DB=crdt -e POSTGRES_USER=crdt -e POSTGRES_PASSWORD=crdt postgres:17-alpine
docker build -t crdt-railway .
docker run --rm -m 512m --network crdt-net -e PORT=8080 -e DATABASE_URL=postgresql://crdt:crdt@crdt-db:5432/crdt -p 8080:8080 crdt-railway
```

Open http://localhost:8080 in two tabs.

## What this deployment does not cover

- One instance only. Live documents are kept in the app's memory, so a second replica would split editors into two groups that never see each other's edits.
- A redeploy restarts the app. Open tabs show "offline" for a few seconds, then reconnect and resend any edits the server had not confirmed.
- No backups beyond what Railway's Postgres volume provides.
