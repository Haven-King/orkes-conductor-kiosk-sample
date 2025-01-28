FROM alpine:3.20

MAINTAINER Orkes Inc <builds@orkes.io>

RUN apk add openjdk23 --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing/
RUN java --version

RUN mkdir -p /app
RUN chown kiosk:kiosk /app

COPY build/libs/kiosk-sample-*.jar /app/kiosk-sample.jar
COPY dockerFiles/startup.sh /app/

RUN chmod +x /app/startup.sh

USER kiosk

CMD ["/app/startup.sh"]
ENTRYPOINT ["/bin/sh"]