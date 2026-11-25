package io.github.wendaoji.iotdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.*;
import static io.github.wendaoji.iotdb.TableRest.JSON;
import static org.junit.jupiter.api.Assertions.*;

class IoTDBSinkTest {
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final AtomicInteger unavailable = new AtomicInteger();
    private String response = "{\"code\":200}";
    private Map<String, String> props;

    static Map<String, String> props(String url) {
        Map<String, String> p = new HashMap<>();
        p.put("iotdb.url", url); p.put("iotdb.user", "test"); p.put("iotdb.password", "test");
        p.put("iotdb.database", "power"); p.put("iotdb.table", "meters");
        p.put("iotdb.columns", "device:STRING:TAG,site:STRING:ATTRIBUTE,current:DOUBLE:FIELD,voltage:INT32:FIELD");
        p.put("iotdb.retry.backoff.ms", "0");
        return p;
    }

    static SinkRecord record(Object value, long offset) {
        Schema schema = value instanceof Struct ? ((Struct) value).schema() : null;
        return new SinkRecord("meters", 0, null, null, schema, value, offset, 1735689600000L + offset, TimestampType.CREATE_TIME);
    }

    @BeforeEach void fixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            paths.add(e.getRequestURI().getPath());
            auth.add(e.getRequestHeaders().getFirst("Authorization"));
            requests.add(JSON.readTree(e.getRequestBody()));
            byte[] reply = response.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(unavailable.getAndDecrement() > 0 ? 503 : 200, reply.length);
            try { e.getResponseBody().write(reply); } finally { e.close(); }
        });
        server.start();
        props = props("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void batchesTypedRowsAndOptionalSchema() throws Exception {
        props.put("iotdb.auto.create", "true"); props.put("iotdb.batch.size", "1");
        IoTDBSinkTask task = new IoTDBSinkTask(); task.start(props);
        task.put(Arrays.asList(record("{\"ts\":\"2025-01-01T00:00:00Z\",\"device\":\"O'Brien\",\"current\":2.5}", 1),
                record(null, 2), record(JSON.readValue("{\"ts\":123,\"device\":\"b\",\"voltage\":4}", Map.class), 3)));
        task.stop();
        assertEquals(Arrays.asList("/rest/table/v1/nonQuery", "/rest/table/v1/nonQuery", "/rest/table/v1/insertTablet", "/rest/table/v1/insertTablet"), paths);
        assertEquals("Basic dGVzdDp0ZXN0", auth.get(0));
        assertTrue(requests.get(1).path("sql").asText().contains("\"site\" STRING ATTRIBUTE"));
        assertEquals(JSON.readTree("[1735689600000]"), requests.get(2).get("timestamps"));
        assertEquals(JSON.readTree("[[\"O'Brien\",null,2.5,null]]"), requests.get(2).get("values"));
        assertEquals(JSON.readTree("[[\"b\",null,null,4]]"), requests.get(3).get("values"));
    }

    @Test void kafkaTimeInMicrosecondsAndStructOnReplay() {
        props.put("iotdb.time.field", ""); props.put("iotdb.timestamp.precision", "us");
        props.put("iotdb.columns", "id:STRING:TAG,n:INT64:FIELD");
        Schema s = SchemaBuilder.struct().field("id", Schema.STRING_SCHEMA).field("n", Schema.INT64_SCHEMA).build();
        SinkRecord input = record(new Struct(s).put("id", "a").put("n", Long.MAX_VALUE), 0);
        IoTDBSinkTask task = new IoTDBSinkTask(); task.start(props);
        task.put(Collections.singletonList(input)); task.put(Collections.singletonList(input));
        assertEquals(1735689600000000L, requests.get(0).at("/timestamps/0").longValue());
        assertEquals(Long.MAX_VALUE, requests.get(0).at("/values/0/1").longValue());
        assertEquals(requests.get(0), requests.get(1));
    }

    @Test void transientFailureRetriesSameTabletAndStopsAtLimit() {
        props.put("iotdb.max.retries", "1");
        IoTDBSinkTask task = new IoTDBSinkTask(); task.start(props);
        List<SinkRecord> rows = Collections.singletonList(record("{\"ts\":1,\"device\":\"A\",\"current\":12.3}", 0));
        unavailable.set(1); task.put(rows);
        assertEquals(requests.get(0), requests.get(1));
        unavailable.set(3); assertThrows(ConnectException.class, () -> task.put(rows));
        assertEquals(4, requests.size());
    }

    @Test void applicationErrorInSuccessfulHttpResponseFailsTask() {
        response = "{\"code\":701,\"message\":\"invalid table\"}";
        IoTDBSinkTask task = new IoTDBSinkTask(); task.start(props);
        ConnectException e = assertThrows(ConnectException.class, () -> task.put(Collections.singletonList(record("{\"ts\":1,\"device\":\"x\",\"voltage\":50}", 1))));
        assertTrue(e.getMessage().contains("701"));
        assertEquals(1, requests.size());
    }

    @Test void rejectsMissingTimeAndOutOfRangeIntegersBeforeWrite() {
        IoTDBSinkTask task = new IoTDBSinkTask(); task.start(props);
        for (String input : Arrays.asList("{\"device\":\"x\"}", "{\"ts\":1,\"device\":\"x\",\"voltage\":2147483648}", "{\"ts\":1,\"voltage\":10}")) {
            assertThrows(DataException.class, () -> task.put(Collections.singletonList(record(input, 10))));
        }
        assertTrue(requests.isEmpty());
    }

    @Test void connectorValidatesBeforeDistributingIndependentTaskConfigs() {
        IoTDBSinkConnector connector = new IoTDBSinkConnector();
        connector.start(props);
        List<Map<String, String>> tasks = connector.taskConfigs(3);
        assertEquals(3, tasks.size()); tasks.get(0).clear(); assertEquals(props, tasks.get(1));
        props.put("iotdb.columns", "device:INT32:TAG,current:FLOAT:FIELD");
        assertThrows(ConfigException.class, () -> connector.start(props));
    }
}
