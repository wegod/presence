# Runtime image for seckill-bootstrap (JDK 21 JRE)
# Expects a host-built Spring Boot jar at docker/app/app.jar
# (avoids Maven Central DNS inside the Colima VM).
# Build context: repository root
#
# Prepare jar first:
#   cd backend && mvn -B -DskipTests -pl seckill-bootstrap -am package
#   cp seckill-bootstrap/target/seckill-bootstrap-*.jar ../docker/app/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY docker/app/app.jar /app/app.jar

ENV JAVA_OPTS="-Xmx3g -Xms1g"
EXPOSE 18178

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
