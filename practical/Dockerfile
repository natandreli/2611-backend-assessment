# Intake image. Build from the repository root, because the build reads the event schema from docs/api:
#   docker build -f practical/Dockerfile -t payment-intake .
# Tests are not run here: CI runs them before the image is built.

FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace
COPY docs/api/events/premium-payment.schema.json docs/api/events/
COPY practical/ practical/
RUN cd practical && sh ./mvnw -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:24-jre
RUN useradd --system --uid 10001 --no-create-home intake
WORKDIR /app
COPY --from=build /workspace/practical/target/payment-intake-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
