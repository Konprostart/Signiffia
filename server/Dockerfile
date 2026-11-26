# Build stage — compiles the server into an install distribution.
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon installDist

# Runtime stage — minimal JRE.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/server /app
ENV PORT=8080
ENV SIGNIFFIA_STORE=postgres
EXPOSE 8080
# The server requires a reachable PostgreSQL database; see README.md for configuration.
ENTRYPOINT ["/app/bin/server"]
