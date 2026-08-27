package com.branch.app.controller;

import com.branch.app.exception.NotFoundException;
import com.branch.app.exception.RateLimitException;
import com.branch.app.model.GithubRepo;
import com.branch.app.model.GithubUser;
import com.branch.app.service.GithubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GithubController.class)
class GithubControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GithubService githubService;

    @MockBean
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    private static final GithubUser TEST_USER = GithubUser.builder()
            .userName("octocat")
            .displayName("The Octocat")
            .avatar("https://avatars.githubusercontent.com/octocat")
            .geoLocation("San Francisco")
            .email("octocat@github.com")
            .url("https://api.github.com/users/octocat")
            .createdAt("Tue, 25 Jan 2011 18:44:36 GMT")
            .repos(List.of(GithubRepo.builder()
                    .name("Hello-World")
                    .url("https://github.com/octocat/Hello-World")
                    .build()))
            .build();

    @Test
    void getUser_success_returns200WithUserJson() throws Exception {
        when(githubService.getUser("octocat")).thenReturn(TEST_USER);

        mockMvc.perform(get("/github/user/octocat").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_name").value("octocat"))
                .andExpect(jsonPath("$.display_name").value("The Octocat"))
                .andExpect(jsonPath("$.repos[0].name").value("Hello-World"));
    }

    @Test
    void getUser_notFound_returns404WithErrorMessage() throws Exception {
        when(githubService.getUser("nobody"))
                .thenThrow(new NotFoundException("GitHub User not found: nobody"));

        mockMvc.perform(get("/github/user/nobody").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("GitHub User not found: nobody"));
    }

    @Test
    void getUser_rateLimited_returns500WithErrorMessage() throws Exception {
        when(githubService.getUser("octocat"))
                .thenThrow(new RateLimitException("GitHub API rate limit of 60 exceeded. Resets at: Thu, 01 Jan 2099 00:00:00 GMT"));

        mockMvc.perform(get("/github/user/octocat").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getUser_unexpectedError_returns500() throws Exception {
        when(githubService.getUser("octocat"))
                .thenThrow(new RuntimeException("Something went wrong"));

        mockMvc.perform(get("/github/user/octocat").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }
}
