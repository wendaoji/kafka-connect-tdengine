package io.github.wendaoji.iotdb;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.kafka.connect.errors.ConnectException;

final class TableRest {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SinkSettings settings;
    private final String authorization;

    TableRest(SinkSettings settings) {
        this.settings = settings;
        String credentials = settings.getString("iotdb.user") + ":" + settings.getPassword("iotdb.password").value();
        authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    ObjectNode sql(String database, String sql) {
        return JSON.createObjectNode().put("database", database).put("sql", sql);
    }

    JsonNode post(String operation, ObjectNode request) {
        byte[] body;
        try { body = JSON.writeValueAsBytes(request); }
        catch (IOException e) { throw new ConnectException("Cannot serialize IoTDB request", e); }
        for (int attempt = 0; ; attempt++) {
            try { return send(operation, body); }
            catch (IOException e) {
                if (attempt >= settings.getInt("iotdb.max.retries")) throw new ConnectException("IoTDB " + operation + " failed after " + (attempt + 1) + " attempt(s)", e);
                try { Thread.sleep(settings.getInt("iotdb.retry.backoff.ms")); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ConnectException("Interrupted while retrying IoTDB " + operation, interrupted);
                }
            }
        }
    }

    private JsonNode send(String operation, byte[] body) throws IOException {
        HttpURLConnection http = (HttpURLConnection) settings.base.resolve("rest/table/v1/" + operation).toURL().openConnection();
        try {
            http.setRequestMethod("POST");
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(settings.getInt("iotdb.timeout.ms"));
            http.setReadTimeout(settings.getInt("iotdb.timeout.ms"));
            http.setRequestProperty("Authorization", authorization);
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setRequestProperty("Accept", "application/json");
            http.setDoOutput(true);
            http.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = http.getOutputStream()) { out.write(body); }
            int status = http.getResponseCode();
            if (status == 408 || status == 429 || status >= 500) throw new IOException("IoTDB returned HTTP " + status);
            if (status < 200 || status >= 300) throw new ConnectException("IoTDB " + operation + " returned HTTP " + status);
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (output.size() + n > 1024 * 1024) throw new ConnectException("IoTDB response exceeds 1 MiB");
                    output.write(buf, 0, n);
                }
                JsonNode reply = JSON.readTree(output.toByteArray());
                if (reply == null || !reply.isObject()) throw new ConnectException("Invalid IoTDB " + operation + " response");
                JsonNode code = reply.path("code");
                if (code.isMissingNode() && operation.equals("query") && reply.path("values").isArray()) return reply;
                if (!code.isIntegralNumber() || code.asLong() != 200) {
                    String detail = reply.path("message").asText("invalid response code");
                    throw new ConnectException("IoTDB " + operation + " code " + code + ": " + detail.substring(0, Math.min(detail.length(), 512)));
                }
                return reply;
            }
        } finally { http.disconnect(); }
    }
}
