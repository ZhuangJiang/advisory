# syntax=docker/dockerfile:1
#
# 构建上下文是仓库根目录而不是模块目录：多模块工程的子模块要能读到父 POM（backend/pom.xml），
# 同时把 Maven 依赖层与源码层分开缓存，这样日常改代码不会重新下载 Spring AI 与 Tika（约 150 MB）。
#
# `MAVEN_MIRROR` 默认指向阿里云中央仓库镜像，因为目标部署环境在国内；
# 传 `--build-arg MAVEN_MIRROR=` 空值即可回退到 Maven Central。

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/central
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>' \
            "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi
COPY backend/pom.xml backend/pom.xml
COPY backend/advisory-server/pom.xml backend/advisory-server/pom.xml
# 尽力而为：这一步失败只影响构建耗时，不影响正确性——漏掉的依赖会在打包阶段补齐。
RUN mvn -B -q -DskipTests -f backend/advisory-server/pom.xml dependency:go-offline || true
COPY backend/advisory-server/src backend/advisory-server/src
RUN mvn -B -q -DskipTests -f backend/advisory-server/pom.xml package \
    && cp backend/advisory-server/target/*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# 完成 COPY 之后，容器不再需要 root 权限。
RUN addgroup -S advisory && adduser -S advisory -G advisory
COPY --from=build /build/app.jar /app/app.jar
USER advisory
EXPOSE 20001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
