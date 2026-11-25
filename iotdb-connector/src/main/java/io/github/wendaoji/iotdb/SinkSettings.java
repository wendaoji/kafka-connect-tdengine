package io.github.wendaoji.iotdb;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import static org.apache.kafka.common.config.ConfigDef.Importance.*;
import static org.apache.kafka.common.config.ConfigDef.Type.*;

final class SinkSettings extends AbstractConfig {
    static final ConfigDef DEF = new ConfigDef()
        .define("iotdb.url", STRING, HIGH, "Base URL of the IoTDB Table REST service")
        .define("iotdb.user", STRING, HIGH, "IoTDB user")
        .define("iotdb.password", PASSWORD, HIGH, "IoTDB password")
        .define("iotdb.database", STRING, HIGH, "Table database")
        .define("iotdb.table", STRING, HIGH, "Target table")
        .define("iotdb.columns", LIST, HIGH, "Comma-separated name:type:category columns")
        .define("iotdb.time.field", STRING, "ts", MEDIUM, "Time field, or empty to use the Kafka record timestamp")
        .define("iotdb.timestamp.precision", STRING, "ms", ConfigDef.ValidString.in("ms", "us", "ns"), MEDIUM, "Server timestamp precision")
        .define("iotdb.auto.create", BOOLEAN, false, MEDIUM, "Create database and table if absent")
        .define("iotdb.batch.size", INT, 250, ConfigDef.Range.between(1, 10000), MEDIUM, "Maximum rows per tablet")
        .define("iotdb.timeout.ms", INT, 10000, ConfigDef.Range.between(1, 120000), MEDIUM, "Connect and read timeout per request")
        .define("iotdb.max.retries", INT, 2, ConfigDef.Range.between(0, 10), MEDIUM, "Retries per request after transport errors")
        .define("iotdb.retry.backoff.ms", INT, 250, ConfigDef.Range.between(0, 30000), LOW, "Delay between retries");

    enum Type { STRING, TEXT, BOOLEAN, INT32, INT64, FLOAT, DOUBLE, TIMESTAMP, DATE }
    enum Category { TAG, ATTRIBUTE, FIELD }
    static final class Column {
        final String source, name;
        final Type type;
        final Category category;
        Column(String spec) {
            String[] parts = spec.trim().split(":", -1);
            if (parts.length != 3) throw new ConfigException("Expected name:type:category in iotdb.columns");
            source = parts[0].trim();
            name = identifier(source);
            try {
                type = Type.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
                category = Category.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) { throw new ConfigException("Unsupported IoTDB column specification: " + spec); }
            if (name.equals("time") || (category != Category.FIELD && type != Type.STRING))
                throw new ConfigException("time is reserved; TAG and ATTRIBUTE columns require STRING: " + spec);
        }
    }
    final URI base;
    final String database, table, timeField;
    final int ticksPerSecond;
    final List<Column> columns = new ArrayList<>();

    SinkSettings(Map<String, String> props) {
        super(DEF, props, false);
        try { base = URI.create(getString("iotdb.url").replaceAll("/+$", "") + "/"); }
        catch (IllegalArgumentException e) { throw new ConfigException("Invalid iotdb.url"); }
        if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new ConfigException("iotdb.url must be an HTTP(S) base URL without credentials, query or fragment");
        if (getString("iotdb.user").isEmpty() || getString("iotdb.user").contains(":")) throw new ConfigException("Invalid iotdb.user");
        database = identifier(getString("iotdb.database"));
        table = identifier(getString("iotdb.table"));
        timeField = getString("iotdb.time.field");
        String precision = getString("iotdb.timestamp.precision");
        ticksPerSecond = precision.equals("ms") ? 1000 : precision.equals("us") ? 1000000 : 1000000000;
        Set<String> names = new HashSet<>();
        boolean hasField = false;
        for (String spec : getList("iotdb.columns")) {
            Column col = new Column(spec);
            if (!names.add(col.name)) throw new ConfigException("Duplicate IoTDB column: " + col.name);
            columns.add(col);
            hasField |= col.category == Category.FIELD;
        }
        if (!hasField) throw new ConfigException("iotdb.columns requires at least one FIELD");
    }

    private static String identifier(String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z_0-9]*")) throw new ConfigException("Invalid IoTDB identifier: " + name);
        return name.toLowerCase(Locale.ROOT);
    }
    String tableDDL() {
        StringJoiner fields = new StringJoiner(", ");
        for (Column col : columns) fields.add("\"" + col.name + "\" " + col.type + " " + col.category);
        return "CREATE TABLE IF NOT EXISTS \"" + table + "\" (" + fields + ")";
    }
}
