FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S payport && adduser -S payport -G payport

WORKDIR /app
COPY gateway-core/target/gateway-core-*.jar app.jar

USER payport

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
