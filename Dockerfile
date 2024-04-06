FROM ubuntu:22.04 AS maelstrom
LABEL authors="consensussolutions"
ARG MAELSTROM_VERSION=v0.2.3

# Update the package lists
RUN apt-get update

# Install necessary packages
RUN apt-get install -y wget bzip2

RUN mkdir -p /app && \
    wget -O /app/maelstrom.tar.bz2 https://github.com/jepsen-io/maelstrom/releases/download/$MAELSTROM_VERSION/maelstrom.tar.bz2 && \
    bzip2 -d /app/maelstrom.tar.bz2 && \
    tar -xf /app/maelstrom.tar -C /app && \
    rm /app/maelstrom.tar

FROM container-registry.oracle.com/graalvm/jdk:22

# Copy maelstrom directory from downloadMaelstrom stage
COPY --from=maelstrom /app/maelstrom /app/

RUN chmod +x /app/maelstrom && \
    microdnf install findutils maven graphviz && \
    microdnf clean all && \
    cd /app/demo/java && \
    mvn package

# RUN microdnf install graphviz && microdnf clean all

ENTRYPOINT ["top", "-b"]
