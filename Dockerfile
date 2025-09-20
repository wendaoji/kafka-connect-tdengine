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


FROM maven:3.9.11-eclipse-temurin-8-alpine as builder
WORKDIR /opt

# Install build dependencies
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apk/repositories \
  && apk update \
  && apk add --no-cache git \
  && mkdir -p tdengine/etc

RUN git clone --branch 3.0 https://github.com/taosdata/kafka-connect-tdengine.git \
  && cd kafka-connect-tdengine \
  && mvn clean package -Dmaven.test.skip=true \
  && unzip -d tdengine target/components/packages/taosdata-kafka-connect-tdengine-*.zip


FROM wendaoji/tdengine-tsdb-oss-client:3.3.7.5-alpine as client
WORKDIR /opt

# https://github.com/apache/kafka/blob/trunk/docker/docker_official_images/3.7.0/jvm/Dockerfile
FROM apache/kafka:3.7.0
USER root
EXPOSE 8083
WORKDIR /opt/kafka

# 1. TD_LIBRARY_PATH: /usr/local/lib # 通过 TD_LIBRARY_PATH 指定， 如果加载出错时，无法显示具体错误，建议直接将动态库挂载到 java 默认加载动态库的目录，如：/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib。
# 2. TD_LIBRARY_PATH 这个参数 3.3.3 中没有，3.6.3 中有
# 3. 使用 LD_LIBRARY_PATH 更标准。
# 如果使用的是 glibc(ubuntu/centos)，则一般需要 libtaos.so libtaosnative.so 即可。
# 如果使用 musl libc(alpine)，则可能还需要 libunwind.so.8 liblzma.so.5 libstdc++.so.6 libgcc_s.so.1
ENV LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-"/opt/tdengine/tdengine-tsdb-oss-client/lib"}

COPY --chown=appuser:appuser --from=builder /opt/tdengine /opt/
COPY --chown=appuser:appuser --from=client /opt/tdengine-tsdb-oss-client /opt/tdengine/tdengine-tsdb-oss-client-3.3.7.5-linux-x64-alpine
COPY --chown=appuser:appuser tdengine/* /opt/tdengine/

# TDEngine jdbc 连接串中指定的 cfgdir 没有起作用，这里指定到默认路径 /etc/taos 下。
# taos.cfg 中必须指定一个可写的日志目录(logDir)，如 logDir /opt/tdengine/logs
RUN set -eux \
  && ln -s /opt/tdengine/taosdata-kafka-connect-tdengine-* /opt/tdengine/taosdata-kafka-connect-tdengine \
  && ln -s /opt/tdengine/tdengine-tsdb-oss-client-* /opt/tdengine/tdengine-tsdb-oss-client \
  && ln -s opt/tdengine/etc/taos.cfg /etc/taos/taos.cfg

VOLUME ["/opt/tdengine"]

USER appuser


CMD [ "/opt/kafka/bin/connect-standalone.sh /opt/tdengine/connect-standalone-tdengine.properties /opt/tdengine/connect-tdengine.json" ]
