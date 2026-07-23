FROM alpine:3.20
COPY target/docker-repository/ /maven-repository/
LABEL io.mvndockerhub.layout="/maven-repository"
