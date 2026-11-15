# GMshield development setup

One Docker Compose file, [`compose.yaml`](compose.yaml), for all three applications. It starts
PostgreSQL, Redis and Qdrant, the backend, and the frontend and the worker once their sibling
clone has a `Dockerfile`. The frontend and the worker keep no setup of their own: they point here.

The clones must sit side by side:

```
agentic-RPG/
  GMshield-backend/    <- this repository, with dev/
  GMshield-frontend/
  GMshield-worker/
```

## Start and stop

```sh
~/agentic-RPG/GMshield-backend/dev/start     # builds what changed, starts everything, waits until healthy
~/agentic-RPG/GMshield-backend/dev/stop      # stops and removes every container; data is kept
```

`dev/start` ends by printing the backend's health and address, the frontend's address and the
worker's state when they started. The frontend and the worker start only after the backend is
healthy. The frontend is itself healthy when its `/api/health` answers 200, and the worker when
its health check (`python -m gmshield_worker.health`) passes: in both cases the backend answered
with a contract version the application accepts. `dev/stop --wipe` also deletes the development
data (database, Redis file, Qdrant storage).

| Service | Address on the workstation | Throwaway credential |
|---|---|---|
| Backend | http://127.0.0.1:8080 (`/actuator/health`, `/api/v1/version`) | none yet |
| PostgreSQL | 127.0.0.1:5432, database `gmshield` | user `gmshield`, password `gmshield-dev` |
| Redis | 127.0.0.1:6379 | password `gmshield-dev` |
| Qdrant | http://127.0.0.1:6333 | API key `gmshield-dev` |
| Frontend | http://127.0.0.1:3000 (`/api/health`) | none |
| Worker | no port: it opens none | `GMSHIELD_ANTHROPIC_API_KEY` from your shell, or the placeholder `not-a-real-key` |

Every port is bound to 127.0.0.1: nothing is reachable from another machine. The credentials are
for development on this machine only. A port that is taken can be moved for one start, for example
`GMSHIELD_DEV_BACKEND_PORT=18080 dev/start` (also `GMSHIELD_DEV_POSTGRES_PORT`,
`GMSHIELD_DEV_REDIS_PORT`, `GMSHIELD_DEV_QDRANT_PORT`, `GMSHIELD_DEV_FRONTEND_PORT`).

The backend, the frontend and the worker run here as they do in production: outside test mode,
with their settings given in `compose.yaml`, as a non-root user on a read-only root filesystem with
a tmpfs on `/tmp`.

The worker refuses to start without `GMSHIELD_ANTHROPIC_API_KEY`. `compose.yaml` passes the value
of that variable from the shell that runs `dev/start`, or the placeholder `not-a-real-key` when it
is unset. Nothing calls the model yet, so the placeholder is enough; to try a real key for one
start, `GMSHIELD_ANTHROPIC_API_KEY=... dev/start` (never write it into a file of this repository).
The worker does not use Redis yet: Story 1.12 adds its Redis account and service credential.

## It never runs on its own

- No container has a restart policy: containers start only by `dev/start`. After a restart of
  Docker, of WSL or of Windows, nothing is running until that command is used again.
- Docker may start when WSL is launched. It never starts when Windows starts, because nothing
  launches WSL by itself: install Docker inside WSL as described below, not Docker Desktop
  (which starts with Windows and keeps WSL running).
- Nothing keeps WSL alive. When the last WSL terminal and editor window are closed, Windows stops
  WSL after about a minute, and Docker and every container stop with it.

Confirm that everything is stopped:

```sh
docker ps                      # in WSL, after dev/stop: no container listed
systemctl is-active docker     # in WSL: "active" while WSL is open is expected and allowed
```

```powershell
wsl --list --running           # in PowerShell, after closing WSL: "There are no running distributions."
wsl --shutdown                 # stops WSL, Docker and every container at once
```

To stop Docker itself while still working in WSL: `sudo systemctl stop docker.socket docker`.

## Install Docker in WSL (once, by hand)

The workstation's WSL distribution is Ubuntu 26.04 with systemd on (`/etc/wsl.conf` has
`[boot] systemd=true`). Install Docker from Ubuntu's own packages, the same packaging as on
roberto-server:

```sh
sudo apt update
sudo apt install docker.io docker-buildx docker-compose-v2
sudo usermod -aG docker "$USER"
```

Then close every WSL window, run `wsl --shutdown` in PowerShell, and open WSL again, so that the
group membership applies. Check:

```sh
docker version                 # client and server both answer
docker compose version
docker buildx version
```

Do not create a Windows scheduled task or start-up entry that opens WSL, and do not install
Docker Desktop: either would break the rule above.

## Notes

- The first `dev/start` downloads the images and builds the backend, which runs its tests: allow
  several minutes. Later starts reuse what did not change.
- `docker compose -f dev/compose.yaml logs -f backend` follows the backend's log (JSON lines);
  `logs -f frontend` follows the frontend's, `logs -f worker` the worker's.
- The first start of the worker builds its image, which downloads Docling's models and runs the
  worker's tests: allow several minutes.
- `docker compose -f dev/compose.yaml exec postgres psql -U gmshield` opens the development database.
