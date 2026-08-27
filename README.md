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
export GITHUB_TOKEN=github_pat_...
docker compose up --build
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password |
| `GITHUB_TOKEN` | _(empty)_ | GitHub Personal Access Token |

## Running tests

```bash
docker compose run --rm test
```

## API

### `GET /github/user/:username`

Returns GitHub user info for the given username.

```bash
curl http://localhost:8080/github/user/octocat
```
