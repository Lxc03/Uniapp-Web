# 用 JDK 21 编译并打包 Spring Boot 应用与 Flyway 迁移。
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --version && mvn --batch-mode --no-transfer-progress package

# 运行容器只携带 JRE 和正式应用 JAR。
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/uniapp-web-api.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
