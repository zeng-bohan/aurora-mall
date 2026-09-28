# docker/ — local infrastructure

Middlewares for the whole project, production-isomorphic with the future
server deployment (ADR-0007).

## Start

```bash
cd docker
docker compose up -d        # first run pulls ~2.5GB of images
bash smoke.sh               # waits, asserts every service healthy
bash nacos/import.sh        # namespace dev + configs/secrets into nacos
```

`import.sh` must run **before the first service start**: services import
`aurora-common.yml` (secrets) from the config center and fail fast without
it. The script generates `docker/.secrets.env` on first run (gitignored) so
real secret values never enter the repository; re-runs reuse it and simply
re-publish the configs.

## Endpoints

| service | url |
| --- | --- |
| MySQL | localhost:13306 (root/aurora123) — host 3306 belongs to another project |
| Redis | localhost:16379 — host 6379 likewise |
| Nacos console | http://localhost:8848/nacos (namespace `dev` holds aurora config) |
| RocketMQ namesrv / broker | localhost:9876 / localhost:10911, dashboard http://localhost:8180 |
| SkyWalking | OAP 11800/12800, UI http://localhost:8090 (9.7 line: H2 storage) |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/aurora123) |
| Loki | http://localhost:3100 |

## Smoke scripts

- `bash smoke.sh` — infra seam: every container healthy or running, plus
  host-side probes for dashboard/ui images that ship no probe binary
- `bash smoke-services.sh` — application seam: gateway + six services answer
  200 through `/api/<service>/actuator/health` (start the services first)

## Notes

- RocketMQ containers run as root: the image ships no store/logs dirs, so
  volume mounts land root-owned
- Port choices above are this dev machine's deviations; in-network traffic
  keeps standard ports
