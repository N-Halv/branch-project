package com.branch.app.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GithubUser {
    private String userName;
    private String displayName;
    private String avatar;
    private String geoLocation;
    private String email;
    private String url;
    private String createdAt;
    private List<GithubRepo> repos;
}
