FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Install git and python3
RUN apt-get update && apt-get install -y git python3

# Copy the build script and source code
COPY build_app.sh .
COPY resolve_spanner_dependencies.sh .
COPY pom.xml .
COPY src ./src

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH

# Run the build script
RUN chmod +x build_app.sh && ./build_app.sh

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/spanner-java-benchmark-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/target/lib ./lib
ENTRYPOINT ["java", "-jar", "app.jar"]
