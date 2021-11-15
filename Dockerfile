FROM golang:1.10.4 AS gobuilder
WORKDIR /compile
RUN export PATH=$PATH:$(go env GOPATH)/bin
RUN GO111MODULE=on GOBIN=`pwd` git clone https://github.com/chrusty/protoc-gen-jsonschema && cd protoc-gen-jsonschema/cmd/protoc-gen-jsonschema/ && git checkout ba5738537335ae5cb42b9362eae2fd13e582a4f4 && go build && cp protoc-gen-jsonschema ~/go/bin/
#go get github.com/chrusty/protoc-gen-jsonschema/cmd/protoc-gen-jsonschema@1.2.2 && go install github.com/chrusty/protoc-gen-jsonschema/cmd/protoc-gen-jsonschema@1.2.2

FROM gradle:6.6-jdk11 AS build
ARG release_version=0.0.0
COPY ./ .
COPY --from=gobuilder /compile ./src/main/resources
RUN gradle --no-daemon clean build dockerPrepare -Prelease_version=${release_version}

FROM adoptopenjdk/openjdk12:jdk-12.0.2_10-slim
ENV CRADLE_INSTANCE_NAME=instance1 \
    CASSANDRA_DATA_CENTER=kos \
    CASSANDRA_HOST=cassandra \
    CASSANDRA_PORT=9042 \
    CASSANDRA_KEYSPACE=demo \
    CASSANDRA_USERNAME=guest \
    CASSANDRA_PASSWORD=guest \
    HTTP_PORT=8080 \
    HTTP_HOST=localhost
WORKDIR /home
COPY --from=gobuilder /compile .
COPY --from=build /home/gradle/build/docker .
ENTRYPOINT ["/home/service/bin/service", "run", "com.exactpro.th2.actuibackend.MainKt"]
