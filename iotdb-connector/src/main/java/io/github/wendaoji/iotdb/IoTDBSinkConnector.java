package io.github.wendaoji.iotdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;

public final class IoTDBSinkConnector extends SinkConnector {
    private Map<String, String> properties;

    @Override public void start(Map<String, String> props) {
        new SinkSettings(props);
        properties = new HashMap<>(props);
    }

    @Override public List<Map<String, String>> taskConfigs(int maxTasks) {
        List<Map<String, String>> tasks = new ArrayList<>();
        for (int i = 0; i < maxTasks; i++) tasks.add(new HashMap<>(properties));
        return tasks;
    }

    @Override public Class<? extends Task> taskClass() { return IoTDBSinkTask.class; }
    @Override public ConfigDef config() { return SinkSettings.DEF; }
    @Override public String version() { return "1.0.0"; }
    @Override public void stop() { properties = null; }
}
