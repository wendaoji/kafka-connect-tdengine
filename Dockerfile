###############################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
###############################################################################


FROM maven:3.9.11-eclipse-temurin-8 AS builder
WORKDIR /opt

ARG VERSION=3.0

ARG UBUNTU_REPO
ARG MIRROR_URL
ENV UBUNTU_REPO ${UBUNTU_REPO:-"mirrors.tuna.tsinghua.edu.cn"}
ENV MIRROR_URL ${MIRROR_URL:-"https://maven.aliyun.com/repository/central"}

# Install build dependencies
RUN set -eux \
  && [ -n "${UBUNTU_REPO}" ] && sed -i "s|archive.ubuntu.com|${UBUNTU_REPO}|g" /etc/apt/sources.list.d/ubuntu.sources; \
  apt-get update \
  && apt-get install -y locales git \
  && rm -rf /var/lib/apt/lists/* \
  && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8 \
  && chmod +x /entrypoint.sh

COPY settings.xml.template /opt/settings.xml.template

RUN sed "s|\${MAVEN_MIRROR_URL}|$MIRROR_URL|g" /opt/settings.xml.template > /opt/settings.xml \
  && git clone --branch ${VERSION} https://github.com/taosdata/kafka-connect-tdengine.git \
  && cd kafka-connect-tdengine \
  && mvn clean package -s /opt/settings.xml -Dmaven.test.skip=true \
  && mv target/components/packages/taosdata-kafka-connect-tdengine-*.zip /opt


FROM wendaoji/tdengine-tsdb-oss-client:latest AS client
WORKDIR /opt

# https://github.com/apache/kafka/blob/trunk/docker/docker_official_images/3.7.0/jvm/Dockerfile
# FROM apache/kafka:3.7.0
ARG KAFKA_VERSION=3.7.0
FROM wendaoji/kafka:${KAFKA_VERSION}
USER root
EXPOSE 8083
WORKDIR /opt/kafka

# 1. TD_LIBRARY_PATH: /usr/local/lib # 通过 TD_LIBRARY_PATH 指定， 如果加载出错时，无法显示具体错误，建议直接将动态库挂载到 java 默认加载动态库的目录，如：/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib。
# 2. TD_LIBRARY_PATH 这个参数 3.3.3 中没有，3.6.3 中有
# 3. 使用 LD_LIBRARY_PATH 更标准。
# 如果使用的是 glibc(ubuntu/centos)，则一般需要 libtaos.so libtaosnative.so 即可。
# 如果使用 musl libc(alpine)，则可能还需要 libunwind.so.8 liblzma.so.5 libstdc++.so.6 libgcc_s.so.1
ARG LD_LIBRARY_PATH
ENV LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-"/opt/tdengine-tsdb-oss-client/lib"}


COPY --chown=appuser:appuser config /opt/tdengine/config
COPY --chown=appuser:appuser --from=builder /opt/taosdata-kafka-connect-tdengine-*.zip /opt/
COPY --chown=appuser:appuser --from=client /usr/local/taos /opt/tdengine-tsdb-oss-client


# TDEngine jdbc 连接串中指定的 cfgdir 没有起作用，这里指定到默认路径 /etc/taos 下。
# taos.cfg 中必须指定一个可写的日志目录(logDir)，如 logDir /opt/tdengine/logs
RUN set -eux \
  && unzip -d /opt/ /opt/taosdata-kafka-connect-tdengine-*.zip \
  && rm -f /opt/taosdata-kafka-connect-tdengine-*.zip \
  && chown -R appuser:appuser /opt/taosdata-kafka-connect-tdengine-* \
  && ln -s /opt/taosdata-kafka-connect-tdengine-* /opt/taosdata-kafka-connect-tdengine \
  && mkdir -p /etc/taos \
  && ln -s /opt/tdengine/config/taos.cfg /etc/taos/taos.cfg

VOLUME ["/opt/tdengine"]

USER appuser

# CMD ["/etc/kafka/docker/run"]
CMD ["/opt/kafka/bin/connect-standalone.sh", "/opt/tdengine/config/connect-standalone-tdengine.properties","/opt/tdengine/config/connect-tdengine.json" ]
