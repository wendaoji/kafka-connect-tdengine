
Kafka Connect TDEngine Container.

The source code on <https://github.com/wendaoji/kafka-connect-tdengine>.

The original Dockfile can be found on the official website <https://github.com/apache/kafka/blob/trunk/docker/docker_official_images/3.7.0/jvm/Dockerfile>.


# run

```bash
docker compose up -d

# get connectors
curl http://localhost:8083/connectors
# delete connectors
curl -X DELETE http://localhost:8083/connectors/TDengineSinkConnector
# post new connectors
curl -X POST -d @connect-tdengine.json http://localhost:8083/connectors -H "Content-Type: application/json"
# test sink
docker compose exec -T kafka-connect-tdengine cat /opt/tdengine/test-data.txt | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --broker-list localhost:9092 --topic meters
```

# config

* `taos.cfg` in `opt/tdengine/etc/`.
* `connect-standalone-tdengine.properties` in `opt/tdengine/`.
* `TDEngine` connector in `opt/tdengine/connect-tdengine.json`.
