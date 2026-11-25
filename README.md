
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

# Apache IoTDB sink

Build the image from this checkout to install the Java 8 compatible Kafka Connect sink for Apache IoTDB 2.x Table REST v1. To install it on another Kafka 3.7 worker, run `mvn -f iotdb-connector/pom.xml package` and place `iotdb-connector/target/kafka-connect-iotdb.jar` in a directory on the worker's `plugin.path`.

Enable IoTDB's REST service and set `iotdb.url` in `config/connect-iotdb.properties` to its address as seen by the worker. Supply a properties file readable by the worker with `user=...` and `password=...`. From the compose network, start the connector using the image you built and the existing worker configuration:

```bash
docker build -t kafka-connect-iotdb:local .
docker compose up -d kafka
docker run --rm --network kafka_kafka \
  -v "$PWD/iotdb-credentials.properties:/run/secrets/iotdb.properties:ro" \
  -v "$PWD/config/connect-iotdb.properties:/opt/connect-iotdb.properties:ro" \
  kafka-connect-iotdb:local /opt/kafka/bin/connect-standalone.sh \
  /opt/connect-standalone-iotdb.properties /opt/connect-iotdb.properties
```

In a second terminal, produce a record. Query the table to verify ingestion.

```bash
echo '{"ts":1735689600000,"device":"m1","site":"north","current":12.5,"voltage":230}' | \
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic iotdb-meters
```

```sql
USE power;
SELECT time, device, site, current, voltage FROM meters WHERE device = 'm1' AND time = 1735689600000;
```

`iotdb.columns` lists the top-level input keys, IoTDB types and column categories in `name:type:category` order. Database, table and column identifiers use ASCII letters, digits and underscores. The source key is case-sensitive and its IoTDB name is lowercased. Every TAG requires a string. ATTRIBUTE accepts a string or null. FIELD supports `INT32`, `INT64`, `FLOAT`, `DOUBLE`, `BOOLEAN`, `STRING`, `TEXT`, `DATE` (ISO date), and `TIMESTAMP`. Missing fields and attributes map to null. The sink accepts JSON objects from StringConverter, UTF-8 JSON bytes, schemaless maps and Connect structs. Kafka tombstones are ignored.

`iotdb.time.field` defaults to `ts`. It accepts an integer in server precision or an ISO instant with a timezone. Set it to an empty string to use Kafka's record timestamp. `iotdb.timestamp.precision` must match the database (`ms` by default, also `us` or `ns`). Replays write to the same TAGs and time. Two events sharing that key update the same row. Use distinct event times if both must be retained.

The default `iotdb.auto.create=false` is suitable for a pre-provisioned database and table. The sample enables it for initial setup. Set `iotdb.batch.size` (default 250) to bound rows per REST tablet. Requests have separate connect and read timeouts of `iotdb.timeout.ms` each (default 10000). I/O failures, HTTP 408, 429 and 5xx get up to `iotdb.max.retries` (default 2), spaced by `iotdb.retry.backoff.ms` (default 250). A rejected tablet or exhausted retry fails the task before Kafka can commit its offsets. Use HTTPS to protect credentials in transit.

Run `mvn -f iotdb-connector/pom.xml test` for the HTTP fixture tests. To include the real round trip, export `IOTDB_TEST_URL`, `IOTDB_TEST_USER`, and `IOTDB_TEST_PASSWORD` for a disposable IoTDB 2.x instance with millisecond precision. The integration test creates a unique database, starts the actual sink task, writes records, verifies their values using Table REST, and drops that database.
