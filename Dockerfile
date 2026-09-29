FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

RUN mkdir -p target/extracted \
 && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted \
 && mkdir -p target/extracted/dependencies \
             target/extracted/spring-boot-loader \
             target/extracted/snapshot-dependencies \
             target/extracted/application

FROM eclipse-temurin:11-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tini \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/target/extracted/application/ ./

RUN mkdir -p /var/lib/trubo-fiesta/store /var/lib/trubo-fiesta/tmp \
 && chown -R app:app /var/lib/trubo-fiesta

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=80 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV APP_STORAGE_ROOT_DIR=/var/lib/trubo-fiesta/store \
    APP_STORAGE_TMP_DIR=/var/lib/trubo-fiesta/tmp

ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.JarLauncher"]
