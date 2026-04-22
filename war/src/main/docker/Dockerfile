FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal

# Install native libraries required for audio codecs:
#   opus      → libopus.so.0  (OGG/Opus audio decoding via FFM)
#   spandsp   → libspandsp.so.2  (G.722 wideband encoding via FFM)
# spandsp is in EPEL 9, not in standard UBI repos — add EPEL first.
USER root
RUN rpm -i https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm \
    && microdnf install -y --nodocs opus spandsp \
    && microdnf clean all
USER 1001

COPY --chown=1001:0 maven/config/* /config/

RUN features.sh

COPY --chown=1001:0 maven/*.war /config/dropins/

ENV OPENJ9_SCC=true
RUN configure.sh

EXPOSE 5060/udp
EXPOSE 5060/tcp
EXPOSE 5061/tcp
EXPOSE 9080/tcp
