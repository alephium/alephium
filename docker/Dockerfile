FROM adoptopenjdk/openjdk11:jre
# Uncomment the next line and comment the previous one if you want to usee GraalVM instaed of OpenJDK
# FROM ghcr.io/graalvm/graalvm-ce:java11-21.0.0.2

ARG RELEASE=0.7.3

RUN curl -L https://github.com/alephium/alephium/releases/download/v${RELEASE}/alephium-${RELEASE}.jar -o /alephium.jar && \
  mkdir -p /alephium-home && usermod -d /alephium-home nobody && chown nobody /alephium-home && \
  mkdir -p ~nobody/.alephium && chown nobody ~nobody/.alephium && \
  mkdir -p ~nobody/.alephium-wallets && chown nobody ~nobody/.alephium-wallets && \
  echo 'alephium.network.network-type = "testnet"' > ~nobody/.alephium/user.conf && \
  echo 'alephium.discovery.bootstrap = ["bootstrap0.alephium.org:9973"]' >> ~nobody/.alephium/user.conf

# http
EXPOSE 12973
# ws
EXPOSE 11973
# rpc
EXPOSE 10973
# p2p??
EXPOSE 9973

USER nobody
ENTRYPOINT java -jar /alephium.jar
