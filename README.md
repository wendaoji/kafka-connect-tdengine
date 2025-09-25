
Kafka Connect TDEngine Container.

The source code on <https://github.com/wendaoji/kafka-connect-tdengine>.

The original Dockfile can be found on the official website <https://github.com/apache/kafka/blob/trunk/docker/docker_official_images/3.7.0/jvm/Dockerfile>.

# build
```bash
docker buildx build -t wendaoji/kafka-connect-tdengine:3.0 .
# or
docker buildx build -t wendaoji/kafka-connect-tdengine:3.0 --progress=plain --platform linux/amd64,linux/arm64 --build-arg VERSION=3.0 --build-arg KAFKA_VERSION=3.7.0 --build-arg UBUNTU_REPO=https://mirrors.tuna.tsinghua.edu.cn --build-arg MAVEN_CENTRAL_REPO=https://maven.aliyun.com/repository/central .
```

# run

```bash
docker compose up -d

# get connectors
curl http://localhost:8083/connectors
# delete connectors
curl -X DELETE http://localhost:8083/connectors/TDengineSinkConnector
# post new connectors
curl -X POST -d @config/connect-tdengine.json http://localhost:8083/connectors -H "Content-Type: application/json"
# test sink
docker compose exec -T kafka-connect-tdengine cat /opt/tdengine/config/test-data.txt | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --broker-list localhost:9092 --topic meters
# query taos database
docker compose exec -T kafka-connect-tdengine /usr/local/taos/bin/taos -h xxxxx -u root -P 6030 -ptaosdata -s "select * from power.meters limit 10"
```

# config

* `taos.cfg` `connect-standalone-tdengine.properties` `connect-tdengine.json` in `/opt/tdengine/config/`.
