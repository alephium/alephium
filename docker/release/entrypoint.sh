#!/usr/bin/env sh

echo "Starting java ${JAVA_NET_OPTS} ${JAVA_MEM_OPTS} ${JAVA_GC_OPTS} ${JAVA_EXTRA_OPTS} -jar /alephium.jar $@"
exec java ${JAVA_NET_OPTS} ${JAVA_MEM_OPTS} ${JAVA_GC_OPTS} ${JAVA_EXTRA_OPTS} -jar /alephium.jar $@
