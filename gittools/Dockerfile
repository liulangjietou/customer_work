FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gittools/pom.xml gittools/pom.xml
COPY gittools/src gittools/src
RUN mvn -q -pl gittools -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/gittools/target/gittools-2.4.0.jar /app/gittools.jar
EXPOSE 3002
ENTRYPOINT ["java", "-jar", "/app/gittools.jar"]
