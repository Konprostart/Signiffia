FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/server /app
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
