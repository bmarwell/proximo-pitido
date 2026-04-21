FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal

COPY --chown=1001:0 maven/config/* /config/

RUN features.sh

COPY --chown=1001:0 maven/*.war /config/dropins/

ENV OPENJ9_SCC=true
RUN configure.sh

EXPOSE 5060/udp
EXPOSE 5060/tcp
EXPOSE 5061/tcp
EXPOSE 9080/tcp
