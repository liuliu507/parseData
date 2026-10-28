package com.liuliu.example.decode.store;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.QueryResponse;

import java.time.temporal.ChronoUnit;

public class ClickHouseClient implements AutoCloseable {

    private final Client client;

    public ClickHouseClient(String endpoint, String username,
                            String password, String database) {
        this.client = new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(database)
                .compressClientRequest(true)
                .setConnectTimeout(30_000)
                .setSocketTimeout(120_000)
                .setMaxConnections(20)
                .setConnectionRequestTimeout(30, ChronoUnit.SECONDS)
                .build();
    }

    public Client raw() {
        return client;
    }

    public void execute(String sql) throws Exception {
        try (QueryResponse ignored = client.query(sql).get()) {
        }
    }

    public TableSchema parseDataSchema() {
        TableSchema schema = new TableSchema();
        schema.addColumn("srcMac", "String");
        schema.addColumn("dstMac", "String");
        schema.addColumn("srcAddr", "String");
        schema.addColumn("dstAddr", "String");
        schema.addColumn("srcPort", "UInt16");
        schema.addColumn("dstPort", "UInt16");
        schema.addColumn("type", "String");
        schema.addColumn("data", "String");
        schema.addColumn("time", "DateTime64(6)");
        schema.addColumn("source_file", "String");
        return schema;
    }

    public TableSchema metaSchema() {
        TableSchema schema = new TableSchema();
        schema.addColumn("file_path", "String");
        schema.addColumn("file_size", "UInt64");
        schema.addColumn("file_mtime", "DateTime64(6)");
        schema.addColumn("status", "String");
        schema.addColumn("retry_count", "UInt32");
        schema.addColumn("packet_count", "UInt64");
        schema.addColumn("last_error", "String");
        schema.addColumn("updated_at", "DateTime64(6)");
        return schema;
    }

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}