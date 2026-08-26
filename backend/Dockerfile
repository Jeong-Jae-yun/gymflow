
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar -x test --no-daemon
RUN cp $(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar') app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --uid 1001 appuser
COPY --from=builder /app/app.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
