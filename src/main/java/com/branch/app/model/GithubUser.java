package com.branch.app.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GithubUser {
    private String username;
    private String name;
    private String bio;
    private int publicRepos;
    private int followers;
    private int following;
}
