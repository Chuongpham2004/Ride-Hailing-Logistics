# syntax=docker/dockerfile:1.7
#
# Shared image for every Spring Boot service under services/<name>.
# Build from the repository root (the Maven reactor needs libs/ and the parent POM):
#
#   docker build -f docker/service.Dockerfile --build-arg SERVICE=trip-service -t rhl-trip-service .

ARG JAVA_VERSION=21

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${SERVICE}" || { echo "SERVICE build-arg is required" >&2; exit 1; } \
    && mvn -B -ntp -pl "services/${SERVICE}" -am package -DskipTests \
    && cp "services/${SERVICE}"/target/*.jar /workspace/app.jar

# Split the fat jar into layers so dependency layers stay cached between releases.
FROM eclipse-temurin:${JAVA_VERSION}-jre AS extract
WORKDIR /workspace
COPY --from=build /workspace/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /workspace/extracted/dependencies/ ./
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract /workspace/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TZ=UTC
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
