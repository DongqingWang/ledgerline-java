FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests package
RUN javac -d /healthcheck docker/Healthcheck.java

FROM eclipse-temurin:21-jre AS runtime-base
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx192m -XX:ActiveProcessorCount=2"
HEALTHCHECK --interval=30s --timeout=8s --start-period=60s --retries=3 CMD ["java", "-Xms8m", "-Xmx16m", "-XX:ActiveProcessorCount=1", "-cp", "/app", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/service.jar"]

# Optional low-disk development path. The default final target still builds in Maven.
# artifacts is a named BuildKit context supplied by scripts/docker_prebuilt.py.
FROM runtime-base AS prebuilt
ARG MODULE
COPY --from=artifacts /${MODULE}-0.1.0.jar /app/service.jar
COPY --from=artifacts /Healthcheck.class /app/Healthcheck.class

FROM runtime-base AS runtime
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/${MODULE}-0.1.0.jar /app/service.jar
COPY --from=build /healthcheck/Healthcheck.class /app/Healthcheck.class
