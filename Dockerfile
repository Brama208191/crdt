# 1. Build the React client
FROM node:20-alpine AS client
WORKDIR /client
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# 2. Build the Spring Boot jar with the client bundled as static files
FROM eclipse-temurin:21-jdk AS server
WORKDIR /server
COPY server/.mvn .mvn
COPY server/mvnw server/pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY server/src src
COPY --from=client /client/dist src/main/resources/static
RUN ./mvnw -q -DskipTests package

# 3. Slim runtime image
FROM eclipse-temurin:21-jre
COPY --from=server /server/target/server-*.jar /app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["java", "-jar", "/app.jar"]
