package com.branch.app.service;

import com.branch.app.exception.NotFoundException;
import com.branch.app.model.GithubRepo;
import com.branch.app.model.GithubUser;
import com.branch.app.model.github.GithubApiRepo;
import com.branch.app.model.github.GithubApiUser;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
public class GithubService {

    private final RestClient restClient;

    public GithubService(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("User-Agent", "branch-app")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }

    public GithubUser getUser(String username) {
        CompletableFuture<GithubApiUser> userFuture = CompletableFuture.supplyAsync(() -> fetchUser(username));
        CompletableFuture<List<GithubApiRepo>> reposFuture = CompletableFuture.supplyAsync(() -> fetchRepos(username));

        GithubApiUser apiUser = joinOrRethrow(userFuture);
        List<GithubApiRepo> apiRepos = joinOrRethrow(reposFuture);

        List<GithubRepo> repos = apiRepos.stream()
                .map(r -> GithubRepo.builder().name(r.getName()).url(r.getUrl()).build())
                .collect(Collectors.toList());

        return GithubUser.builder()
                .userName(apiUser.getLogin())
                .displayName(apiUser.getName())
                .avatar(apiUser.getAvatarUrl())
                .geoLocation(apiUser.getLocation())
                .email(apiUser.getEmail())
                .url(apiUser.getUrl())
                .createdAt(formatDate(apiUser.getCreatedAt()))
                .repos(repos)
                .build();
    }

    private GithubApiUser fetchUser(String username) {
        try {
            return restClient.get()
                    .uri("/users/{username}", username)
                    .retrieve()
                    .body(GithubApiUser.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("GitHub User not found: " + username);
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
        }
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
