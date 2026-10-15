FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S oficina && adduser -S oficina -G oficina
COPY --from=build /app/target/*.jar app.jar

# Tracer da Datadog, mesmo padrao do OS Service: sempre na imagem, carregado so quando o overlay
# de nuvem acrescenta -javaagent ao JAVA_OPTS. Versao fixada para que dois builds do mesmo commit
# embarquem o mesmo tracer.
ARG DD_TRACER_VERSION=1.66.0
ADD --chown=oficina:oficina \
  https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_TRACER_VERSION}/dd-java-agent-${DD_TRACER_VERSION}.jar \
  /app/dd-java-agent.jar

RUN chown oficina:oficina app.jar && chmod 0444 /app/dd-java-agent.jar
USER oficina:oficina

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# 8080 = API. 8081 = actuator quando MANAGEMENT_SERVER_PORT esta definido (nuvem).
EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:${MANAGEMENT_SERVER_PORT:-8080}/actuator/health/liveness >/dev/null || exit 1

# exec: java assume PID 1 e recebe SIGTERM direto no rolling update.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
