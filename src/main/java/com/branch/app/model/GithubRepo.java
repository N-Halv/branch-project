package com.branch.app.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GithubRepo {
    private String name;
    private String url;
}
