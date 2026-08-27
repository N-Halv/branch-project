# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

We are building a Java Spring Boot project. Even though it is simple, it should be production-ready, and the project organization must be ready to be built out into a full Spring Boot application. Even if we are just starting with one API endpoint, the structure should be easily extensible.

The first endpoint we're going to build should be called /github/user/:username. We can start out by just stubbing that endpoint with a fake response.

Set up the development environment so that we just run one Docker Compose, which will start our application along with creating a Redis server. We want to be able to pass the Redis credentials into the Spring Boot service in the environment variables.

Update the README with startup instructions.