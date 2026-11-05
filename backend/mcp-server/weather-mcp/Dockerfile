# syntax=docker/dockerfile:1
#
# 与 advisory-server 一样，构建上下文是仓库根目录：多模块构建需要父 POM 与聚合 POM 同时可见。
# 依赖层与源码层分开缓存，改代码不会重新下载 Spring AI / MCP SDK。
#
# `MAVEN_MIRROR` 默认指向阿里云中央仓库镜像；传 `--build-arg MAVEN_MIRROR=` 空值回退到 Maven Central。

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/central
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>' \
            "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi
COPY backend/pom.xml backend/pom.xml
COPY backend/mcp-server/pom.xml backend/mcp-server/pom.xml
COPY backend/mcp-server/weather-mcp/pom.xml backend/mcp-server/weather-mcp/pom.xml
# 尽力而为：失败只影响构建耗时，漏掉的依赖会在打包阶段补齐。
RUN mvn -B -q -DskipTests -f backend/mcp-server/weather-mcp/pom.xml dependency:go-offline || true
COPY backend/mcp-server/weather-mcp/src backend/mcp-server/weather-mcp/src
RUN mvn -B -q -DskipTests -f backend/mcp-server/weather-mcp/pom.xml package \
    && cp backend/mcp-server/weather-mcp/target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S weather && adduser -S weather -G weather
COPY --from=build /build/app.jar /app/app.jar
USER weather
EXPOSE 21001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
