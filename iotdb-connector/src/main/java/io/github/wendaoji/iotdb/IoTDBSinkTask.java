package io.github.wendaoji.iotdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import static io.github.wendaoji.iotdb.TableRest.JSON;

public final class IoTDBSinkTask extends SinkTask {
    private SinkSettings cfg;
    private TableRest rest;

    @Override public String version() { return "1.0.0"; }
    @Override public void start(Map<String, String> props) {
        cfg = new SinkSettings(props);
        rest = new TableRest(cfg);
        if (cfg.getBoolean("iotdb.auto.create")) {
            rest.post("nonQuery", rest.sql("", "CREATE DATABASE IF NOT EXISTS \"" + cfg.database + "\""));
            rest.post("nonQuery", rest.sql(cfg.database, cfg.tableDDL()));
        }
    }

    @Override public void put(Collection<SinkRecord> records) {
        ObjectNode batch = newBatch();
        for (SinkRecord record : records) {
            if (record.value() == null) continue;  // A Kafka tombstone does not identify a time-series row to delete.
            try {
                JsonNode value = object(record.value());
                long timestamp = cfg.timeField.isEmpty() ? kafkaTime(record.timestamp()) : time(value.path(cfg.timeField));
                ArrayNode row = JSON.createArrayNode();
                for (SinkSettings.Column col : cfg.columns) row.add(cell(col, value.path(col.source)));
                ((ArrayNode) batch.get("timestamps")).add(timestamp);
                ((ArrayNode) batch.get("values")).add(row);
            } catch (IOException | RuntimeException e) {
                throw new DataException("Invalid record at " + record.topic() + "-" + record.kafkaPartition() + "@" + record.kafkaOffset(), e);
            }
            if (batch.get("timestamps").size() == cfg.getInt("iotdb.batch.size")) {
                rest.post("insertTablet", batch);
                batch = newBatch();
            }
        }
        if (batch.get("timestamps").size() > 0) rest.post("insertTablet", batch);
    }

    private ObjectNode newBatch() {
        ObjectNode batch = JSON.createObjectNode().put("database", cfg.database).put("table", cfg.table);
        ArrayNode names = batch.putArray("column_names");
        ArrayNode types = batch.putArray("data_types");
        ArrayNode categories = batch.putArray("column_categories");
        for (SinkSettings.Column col : cfg.columns) {
            names.add(col.name);
            types.add(col.type.name());
            categories.add(col.category.name());
        }
        batch.putArray("timestamps");
        batch.putArray("values");
        return batch;
    }

    private JsonNode object(Object input) throws IOException {
        JsonNode value;
        if (input instanceof String) value = JSON.readTree((String) input);
        else if (input instanceof byte[]) value = JSON.readTree((byte[]) input);
        else if (input instanceof Map) value = JSON.valueToTree(input);
        else if (input instanceof Struct) {
            Struct struct = (Struct) input;
            ObjectNode object = JSON.createObjectNode();
            copyField(struct, cfg.timeField, object);
            for (SinkSettings.Column col : cfg.columns) copyField(struct, col.source, object);
            value = object;
        } else throw new DataException("Expected a JSON object, Map or Struct");
        if (value == null || !value.isObject()) throw new DataException("Expected a JSON object");
        return value;
    }

    private void copyField(Struct struct, String key, ObjectNode object) {
        Field field = struct.schema().field(key);
        if (field != null) object.set(key, JSON.valueToTree(struct.get(field)));
    }

    private long kafkaTime(Long millis) {
        if (millis == null) throw new DataException("Kafka record has no timestamp");
        return Math.multiplyExact(millis, cfg.ticksPerSecond / 1000L);
    }

    private long time(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        if (!value.isTextual()) throw new DataException("Expected integer timestamp or ISO instant");
        Instant t = Instant.parse(value.textValue());
        int nanosPerTick = 1000000000 / cfg.ticksPerSecond;
        if (t.getNano() % nanosPerTick != 0) throw new DataException("Timestamp exceeds configured precision");
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), (long) cfg.ticksPerSecond), t.getNano() / nanosPerTick);
    }

    private JsonNode cell(SinkSettings.Column col, JsonNode v) {
        if (v.isMissingNode() || v.isNull()) {
            if (col.category == SinkSettings.Category.TAG) throw new DataException("Missing TAG " + col.source);
            return JSON.nullNode();
        }
        switch (col.type) {
            case TEXT: case STRING: if (v.isTextual()) return v; break;
            case BOOLEAN: if (v.isBoolean()) return v; break;
            case INT32: if (v.isIntegralNumber() && v.canConvertToInt()) return v; break;
            case INT64: if (v.isIntegralNumber() && v.canConvertToLong()) return v; break;
            case FLOAT: if (v.isNumber() && Float.isFinite(v.floatValue())) return v; break;
            case DOUBLE: if (v.isNumber() && Double.isFinite(v.doubleValue())) return v; break;
            case TIMESTAMP: return JSON.getNodeFactory().numberNode(time(v));
            case DATE:
                if (v.isTextual()) {
                    LocalDate d = LocalDate.parse(v.textValue());
                    if (d.getYear() > 0 && d.getYear() <= 9999) return JSON.getNodeFactory().numberNode(Integer.parseInt(d.format(DateTimeFormatter.BASIC_ISO_DATE)));
                }
                break;
            default: break;
        }
        throw new DataException("Column " + col.source + " requires " + col.type);
    }

    @Override public void stop() { rest = null; }
}
