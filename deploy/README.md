# Deployment files

Systemd unit files for hosting Syncler on a Linux box (the AWS test
instance is the reference deployment).

## Why this directory exists

Before 2026-05-26, deployment ops lived implicitly — somebody put
files on the box and the only trace was in `/etc/systemd/system/`
where they couldn't be diffed against history. A partner-impacting
outage that day (containers brought up on the wrong ports after a
`docker container prune`, uvicorn crash-looping) traced directly to
the gap: the loopback override compose file was misnamed
(`docker-compose.prod.yml` on a test box made operators skip it),
and nothing in the systemd unit graph bound the docker stack to
syncler.service.

The fix is in three parts:

1. `server/docker-compose.loopback.yml` — the loopback-port override,
   under a name a test operator won't skip
2. `server/.env.example` — documents `COMPOSE_FILE=...` so bare
   `docker compose up` picks up both files automatically
3. This directory — `syncler-stack.service` brings the docker stack
   up at boot, `syncler.service` `Requires=` it. One ordered chain.

## Files

- `syncler-stack.service` — `Type=oneshot` unit that runs
  `docker compose --env-file .env up -d` in `/opt/syncler/server`.
  Enable at boot so a host restart brings the stack back without
  human intervention.
- `syncler.service` — uvicorn unit, `Requires=syncler-stack.service`.

## Install (or refresh) on the box

```sh
sudo cp deploy/syncler-stack.service /etc/systemd/system/
sudo cp deploy/syncler.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now syncler-stack.service
sudo systemctl restart syncler.service
sudo systemctl status syncler-stack.service syncler.service
```

`syncler-stack.service` requires `/opt/syncler/server/.env` to
contain `COMPOSE_FILE=docker-compose.yml:docker-compose.loopback.yml`
(see `server/.env.example`). Without it, the stack will come up on
the base compose's dev ports (5433/6380) and uvicorn will fail to
connect.

## Test the wiring

```sh
sudo docker container rm -f server-postgres-1 server-redis-1
sudo systemctl restart syncler-stack.service
docker ps --format '{{.Names}}|{{.Ports}}'   # expect 127.0.0.1:65432 / 65379
sudo systemctl restart syncler.service
curl -sf https://<deploy>/v1/server/webhook-public-key
```

If the curl call returns the public key, the chain is wired correctly.
