####
# Dockerfile para build JVM (modo padrão — mais compatível).
#
# Para build nativo com GraalVM (imagem menor, startup mais rápido):
#   docker build -f Dockerfile.native -t quarkus-radar-monitorasp:native .
#
# Build e execução:
#   docker build -t quarkus-radar-rondon:latest .
#   docker run -p 8091:8091 quarkus-radar-monitorasp:latest
####

# ─── Stage 1: Build ──────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copia apenas o pom.xml primeiro para cache de dependências
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia o código-fonte e compila
COPY src ./src
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copia o runner gerado pelo Quarkus
COPY --from=build /app/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/target/quarkus-app/*.jar ./
COPY --from=build /app/target/quarkus-app/app/ ./app/
COPY --from=build /app/target/quarkus-app/quarkus/ ./quarkus/

# Porta da aplicação
EXPOSE 8091

# Usuário não-root por segurança
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Variáveis de ambiente com valores padrão (sobrescreva no docker-compose)
ENV QUARKUS_PROFILE=prod \
    MONGODB_URI=mongodb://192.168.0.155:27017/ \
    MONGODB_DATABASE=Veiculos \
    RABBITMQ_HOST=rabbitmq \
    RABBITMQ_PORT=5672 \
    RABBITMQ_USERNAME=guest \
    RABBITMQ_PASSWORD=guest

# O Quarkus já reconhece a variável QUARKUS_PROFILE automaticamente,
# então podemos simplificar o comando de inicialização
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]