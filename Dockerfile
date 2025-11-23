FROM gradle:8.5-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle installDist -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
RUN mkdir /app
COPY --from=build /home/gradle/src/build/install/limit-order-book /app/
WORKDIR /app/bin
EXPOSE 8080
CMD ["./limit-order-book"]
