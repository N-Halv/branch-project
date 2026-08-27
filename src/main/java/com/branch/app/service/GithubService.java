package com.branch.app.service;

import com.branch.app.exception.NotFoundException;
import com.branch.app.exception.RateLimitException;
import com.branch.app.model.GithubRepo;
import com.branch.app.model.GithubUser;
import com.branch.app.model.github.GithubApiRepo;
import com.branch.app.model.github.GithubApiUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GithubService {

    private static final Logger log = LoggerFactory.getLogger(GithubService.class);
    private static final int CACHE_TTL_MINUTES = 20;
    private static final String CACHE_KEY_PREFIX = "github:user:";
    private static final String NOT_FOUND_SENTINEL = "__NOT_FOUND__";

    private final RestClient restClient;
    private final RedisTemplate<String, Object> redisTemplate;

    public GithubService(RestClient.Builder builder,
                         RedisTemplate<String, Object> redisTemplate,
                         @Value("${github.token:}") String githubToken) {
        RestClient.Builder clientBuilder = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("User-Agent", "branch-app")
                .defaultHeader("Accept", "application/vnd.github+json");

        if (!githubToken.isBlank()) {
            clientBuilder.defaultHeader("Authorization", "Bearer " + githubToken);
        }

        this.restClient = clientBuilder.build();
        this.redisTemplate = redisTemplate;
    }

    public GithubUser getUser(String username) {
        String cacheKey = CACHE_KEY_PREFIX + username;

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (NOT_FOUND_SENTINEL.equals(cached)) {
                throw new NotFoundException("GitHub User not found: " + username);
            }
            if (cached instanceof GithubUser user) {
                return user;
            }
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis is unavailable, fetching directly from GitHub: {}", e.getMessage());
            return getUserFromGithub(username);
        }

        try {
            GithubUser user = getUserFromGithub(username);
            tryCacheSet(cacheKey, user);
            return user;
        } catch (NotFoundException e) {
            tryCacheSet(cacheKey, NOT_FOUND_SENTINEL);
            throw e;
        }
    }

    private void tryCacheSet(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis is unavailable, result will not be cached: {}", e.getMessage());
        }
    }


    public GithubUser getUserFromGithub(String username) {
        GithubApiUser apiUser = fetchUser(username);
        List<GithubApiRepo> apiRepos = fetchRepos(username);

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

    private static final int REPOS_PER_PAGE = 100;

    private List<GithubApiRepo> fetchRepos(String username) {
        List<GithubApiRepo> allRepos = new java.util.ArrayList<>();
        int page = 1;

        try {
            while (true) {
                final int currentPage = page;
                GithubApiRepo[] repos = restClient.get()
                        .uri("/users/{username}/repos?per_page={perPage}&page={page}",
                                username, REPOS_PER_PAGE, currentPage)
                        .retrieve()
                        .body(GithubApiRepo[].class);

                if (repos == null || repos.length == 0) {
                    break;
                }

                allRepos.addAll(Arrays.asList(repos));

                if (repos.length < REPOS_PER_PAGE) {
                    break;
                }

                page++;
            }
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("GitHub User not found: " + username);
        } catch (HttpClientErrorException.Forbidden e) {
            throw buildRateLimitException(e);
        }

        return allRepos;
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

    private String formatDate(String isoDate) {
        if (isoDate == null) return null;
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.parse(isoDate));
    }
}
