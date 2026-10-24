# ---------- Étape 1 : compilation (JDK complet + Maven Wrapper) ----------
FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dépendances d'abord : cette couche reste en cache tant que pom.xml ne change pas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Puis le code, et fabrication du JAR (les tests tournent à part, avec ./mvnw verify)
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && cp target/dq-analyzer-*.jar /src/app.jar

# ---------- Étape 2 : exécution (JRE seul, utilisateur sans droits) ----------
FROM docker.io/library/eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system dq && useradd --system --gid dq --no-create-home dq \
    && mkdir -p /data /app/logs && chown -R dq:dq /data /app
WORKDIR /app

COPY --from=build --chown=dq:dq /src/app.jar app.jar

USER dq
ENV DQ_STORAGE_PATH=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
