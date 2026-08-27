# branch-project

Engineering Exercise for Branch

## Startup

```bash
docker compose up --build
```

This starts the Spring Boot app on port `8080` and a Redis instance on port `6379`.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password |

## API

### `GET /github/user/:username`

Returns GitHub user info for the given username.

```bash
curl http://localhost:8080/github/user/octocat
```
