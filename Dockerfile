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
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY server/src src
COPY --from=client /client/dist src/main/resources/static
RUN ./mvnw -q -DskipTests package

# 3. Slim runtime image, running as an unprivileged user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app app
WORKDIR /app
COPY --from=server /server/target/server-*.jar app.jar
COPY deploy/start.sh start.sh
RUN sed -i 's/\r$//' start.sh && chmod 755 start.sh
# A fixed heap keeps the bill predictable: the host's memory limit is far above what the app needs.
# Tiered compilation stopped at C1 and Serial GC trade peak speed for a faster start after sleeping.
ENV JAVA_OPTS="-Xms64m -Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=128m"
USER app
EXPOSE 8080
CMD ["/app/start.sh"]
