# branch-project

Engineering Exercise for Branch

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/)

## Running locally

```bash
docker compose up --build
```

This starts the Spring Boot app on port `8080` and a Redis instance on port `6379`.

## Running locally with a GitHub token

Providing a GitHub Personal Access Token raises the API rate limit from 60 to 5,000 requests per hour.

```bash
GITHUB_TOKEN=ghp_<TOKEN> docker compose up --build
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password |
| `GITHUB_TOKEN` | _(empty)_ | GitHub Personal Access Token |

## Clearing Redis
We use redis for caching requests from Github and for keeping track of ratelimiting so it might be useful to flush clear redis data when developing locally.
```bash
docker compose exec redis redis-cli FLUSHALL
```

## Running tests

```bash
docker compose run --rm test
```

## Rate limiting

Requests are rate limited per IP address to **100 requests per 30-minute window**, tracked in Redis. Every response includes the following headers:

| Header | Description |
|---|---|
| `X-RateLimit-Limit` | Maximum requests allowed per window |
| `X-RateLimit-Remaining` | Requests remaining in the current window |

When the limit is exceeded, the API returns `429 Too Many Requests`. The window resets automatically after 30 minutes. If Redis is unavailable, rate limiting is bypassed and requests are allowed through.

## API

### `GET /github/user/:username`

Returns GitHub user info for the given username.

```bash
curl http://localhost:8080/github/user/octocat
```
