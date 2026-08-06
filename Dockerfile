FROM eclipse-temurin:25-jdk AS build
COPY . /src
WORKDIR /src
RUN sh ./gradlew installDist -x test --no-daemon

FROM eclipse-temurin:25-jre
RUN mkdir /app
COPY --from=build /src/build/install/limit-order-book /app/
WORKDIR /app/bin
EXPOSE 8080
CMD ["./limit-order-book"]
