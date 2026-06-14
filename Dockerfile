# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/customer-work.jar app.jar
EXPOSE 8080
# 通过环境变量注入密钥与开关（见 .env.example）
ENTRYPOINT ["java", "-jar", "app.jar"]
