package com.branch.app.model.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubApiUser {
    private String login;
    private String name;
    private String avatarUrl;
    private String location;
    private String email;
    private String url;
    private String createdAt;
}
