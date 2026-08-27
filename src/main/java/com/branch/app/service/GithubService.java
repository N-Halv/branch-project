package com.branch.app.service;

import com.branch.app.exception.NotFoundException;
import com.branch.app.exception.RateLimitException;
import com.branch.app.model.GithubRepo;
import com.branch.app.model.GithubUser;
import com.branch.app.model.github.GithubApiRepo;
import com.branch.app.model.github.GithubApiUser;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GithubService {

    private static final int CACHE_TTL_MINUTES = 20;
    private static final String CACHE_KEY_PREFIX = "github:user:";
    private static final String NOT_FOUND_SENTINEL = "__NOT_FOUND__";

    private final RestClient restClient;
    private final RedisTemplate<String, Object> redisTemplate;

    public GithubService(RestClient.Builder builder, RedisTemplate<String, Object> redisTemplate) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("User-Agent", "branch-app")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        this.redisTemplate = redisTemplate;
    }

    public GithubUser getUser(String username) {
        String cacheKey = CACHE_KEY_PREFIX + username;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (NOT_FOUND_SENTINEL.equals(cached)) {
            throw new NotFoundException("GitHub User not found: " + username);
        }
        if (cached instanceof GithubUser user) {
            return user;
        }

        try {
            GithubUser user = getUserFromGithub(username);
            redisTemplate.opsForValue().set(cacheKey, user, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            return user;
        } catch (NotFoundException e) {
            redisTemplate.opsForValue().set(cacheKey, NOT_FOUND_SENTINEL, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            throw e;
        }
    }


    public GithubUser getUserFromGithub(String username) {
        CompletableFuture<GithubApiUser> userFuture = CompletableFuture.supplyAsync(() -> fetchUser(username));
        CompletableFuture<List<GithubApiRepo>> reposFuture = CompletableFuture.supplyAsync(() -> fetchRepos(username));

        GithubApiUser apiUser = joinOrRethrow(userFuture);
        List<GithubApiRepo> apiRepos = joinOrRethrow(reposFuture);

        List<GithubRepo> repos = apiRepos.stream()
                .map(r -> GithubRepo.builder().name(r.getName()).url(r.getUrl()).build())
                .collect(Collectors.toList());

        GithubUser user = GithubUser.builder()
                .userName(apiUser.getLogin())
                .displayName(apiUser.getName())
                .avatar(apiUser.getAvatarUrl())
                .geoLocation(apiUser.getLocation())
                .email(apiUser.getEmail())
                .url(apiUser.getUrl())
                .createdAt(formatDate(apiUser.getCreatedAt()))
                .repos(repos)
                .build();

        return user;
    }

    private GithubApiUser fetchUser(String username) {
        try {
            return restClient.get()
                    .uri("/users/{username}", username)
                    .retrieve()
                    .body(GithubApiUser.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("GitHub User not found: " + username);
        } catch (HttpClientErrorException.Forbidden e) {
            throw buildRateLimitException(e);
        }
    }

    private List<GithubApiRepo> fetchRepos(String username) {
        try {
            GithubApiRepo[] repos = restClient.get()
                    .uri("/users/{username}/repos", username)
                    .retrieve()
                    .body(GithubApiRepo[].class);
            return repos != null ? Arrays.asList(repos) : List.of();
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("GitHub User not found: " + username);
        } catch (HttpClientErrorException.Forbidden e) {
            throw buildRateLimitException(e);
        }
    }

    private RuntimeException buildRateLimitException(HttpClientErrorException.Forbidden e) {
        var headers = e.getResponseHeaders();
        if (headers == null) {
            return e;
        }

        String resetRemainingHeader = headers.getFirst("X-RateLimit-Remaining");
        String resetTimeHeader = headers.getFirst("X-RateLimit-Reset");
        String limitHeader = headers.getFirst("x-ratelimit-limit");

        if (!"0".equals(resetRemainingHeader)) {
            return e;
        }

        if (resetTimeHeader != null) {
            String resetTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    Instant.ofEpochSecond(Long.parseLong(resetTimeHeader)).atZone(ZoneId.of("UTC")));
            return new RateLimitException("GitHub API rate limit of " + limitHeader + " exceeded. Resets at: " + resetTime);
        }
        return new RateLimitException("GitHub API rate limit exceeded");
    }

    private <T> T joinOrRethrow(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NotFoundException nfe) {
                throw nfe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private String formatDate(String isoDate) {
        if (isoDate == null) return null;
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.parse(isoDate));
    }
}
