# Сборка: зависимости отдельным слоем, чтобы при правке кода не скачивать их заново
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app \
    && mkdir /data && chown app /data
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app

ENV SPRING_PROFILES_ACTIVE=prod
# VPS с 1 ГБ делим с Xray, 3x-ui и nginx. С этими флагами процесс занимает ~250 МБ (куча реально ~60 МБ).
# TieredStopAtLevel=1: только быстрый JIT, меньше памяти под компиляцию, для такой нагрузки разницы нет
ENV JAVA_OPTS="-Xmx160m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
