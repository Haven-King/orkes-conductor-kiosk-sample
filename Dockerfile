FROM alpine:3.20

RUN apk add openjdk21 --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing/
RUN java --version

RUN mkdir -p /app

RUN addgroup -S kiosk && adduser -S kiosk -G kiosk

RUN chown kiosk:kiosk /app

COPY build/libs/kiosk-sample-*-all.jar /app/kiosk-sample.jar
COPY dockerFiles/startup.sh /app/

RUN chmod +x /app/startup.sh

USER kiosk

CMD ["/app/startup.sh"]
ENTRYPOINT ["/bin/sh"]