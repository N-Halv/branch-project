package com.branch.app.controller;

import com.branch.app.model.GithubUser;
import com.branch.app.service.GithubService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/github")
@RequiredArgsConstructor
public class GithubController {

    private final GithubService githubService;

    @GetMapping("/user/{username}")
    public ResponseEntity<GithubUser> getUser(@PathVariable String username) {
        return ResponseEntity.ok(githubService.getUser(username));
    }
}
