FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --chmod=0444 target/queuebridge-1.0.0.jar app.jar
USER 10001:10001
ENTRYPOINT ["java","-jar","app.jar"]
