package com.branch.app.service;

import com.branch.app.model.GithubUser;
import org.springframework.stereotype.Service;

@Service
public class GithubService {

    public GithubUser getUser(String username) {
        // Stubbed — replace with real GitHub API call
        return GithubUser.builder()
                .username(username)
                .name("Stub User")
                .bio("This is a stubbed response")
                .publicRepos(42)
                .followers(100)
                .following(50)
                .build();
    }
}
