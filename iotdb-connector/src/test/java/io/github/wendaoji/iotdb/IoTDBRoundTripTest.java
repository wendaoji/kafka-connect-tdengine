package io.github.wendaoji.iotdb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class IoTDBRoundTripTest {
    @Test @EnabledIfEnvironmentVariable(named = "IOTDB_TEST_URL", matches = ".+")
    void sinkTaskWritesAndReplaysWithoutDuplicatingRows() {
        Map<String, String> p = IoTDBSinkTest.props(System.getenv("IOTDB_TEST_URL"));
        String database = "kafka_test_" + UUID.randomUUID().toString().replace("-", "");
        p.put("iotdb.database", database);
        p.put("iotdb.user", System.getenv("IOTDB_TEST_USER"));
        p.put("iotdb.password", System.getenv("IOTDB_TEST_PASSWORD"));
        p.put("iotdb.auto.create", "true");
        TableRest reader = new TableRest(new SinkSettings(p));
        IoTDBSinkTask task = new IoTDBSinkTask();
        try {
            task.start(p);
            List<SinkRecord> rows = Arrays.asList(
                IoTDBSinkTest.record("{\"ts\":1735689600000,\"device\":\"a\",\"site\":\"north\",\"current\":2.5,\"voltage\":230}", 0),
                IoTDBSinkTest.record("{\"ts\":1735689600001,\"device\":\"b\",\"site\":\"south\",\"current\":3.75,\"voltage\":220}", 1));
            task.put(rows);
            task.put(rows);
            JsonNode got = reader.post("query", reader.sql(database, "SELECT device,site,current,voltage FROM meters ORDER BY time LIMIT 3").put("row_limit", 3));
            JsonNode data = got.path("values");
            assertEquals(2, data.size(), got.toString());
            assertEquals("a", data.at("/0/0").asText());
            assertEquals("north", data.at("/0/1").asText());
            assertEquals(2.5, data.at("/0/2").doubleValue());
            assertEquals(230, data.at("/0/3").intValue());
            assertEquals("b", data.at("/1/0").asText());
            assertEquals("south", data.at("/1/1").asText());
            assertEquals(3.75, data.at("/1/2").doubleValue());
            assertEquals(220, data.at("/1/3").intValue());
        } finally {
            task.stop();
            reader.post("nonQuery", reader.sql("", "DROP DATABASE IF EXISTS " + database));
        }
    }
}
