# syntax=docker/dockerfile:1
#
# Build context is the repository root, not `backend/`: the pom and the sources both have to be
# visible, and Maven's dependency layer is cached separately from the source layer so an ordinary
# code change does not re-download Spring AI and Tika (~150 MB).
#
# `MAVEN_MIRROR` defaults to the Aliyun central mirror because the target deployment is in China;
# pass `--build-arg MAVEN_MIRROR=` to fall back to Maven Central.

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/central
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>' \
            "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi
COPY backend/pom.xml ./
# Best effort: a failure here only costs build time, never correctness — the package step resolves
# whatever was missed.
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY backend/src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# The container never needs root after the copy.
RUN addgroup -S advisory && adduser -S advisory -G advisory
COPY --from=build /build/app.jar /app/app.jar
USER advisory
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
