package com.liuliu.example.decode.store;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class ClickHouseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseClient.class);

    private final Client client;
    private final String database;

    public ClickHouseClient(String endpoint, String username,
                            String password, String database) {
        this.database = database;
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

    public String database() {
        return database;
    }

    public void execute(String sql) throws Exception {
        client.queryAll(sql);
    }

    /** 从 CH DESCRIBE TABLE 动态拉 schema，启动时校验 fail-fast */
    public TableSchema fetchSchema(String tableName) throws Exception {
        String sql = String.format("DESCRIBE TABLE %s.%s", database, tableName);
        List<GenericRecord> rows = client.queryAll(sql);
        TableSchema schema = new TableSchema();
        for (GenericRecord row : rows) {
            schema.addColumn(row.getString("name"), row.getString("type"));
        }
        log.info("从 CH 动态加载 schema：{}.{}（{} 列）", database, tableName,
                schema.getColumns().size());
        return schema;
    }

    /** 校验 CH 真实 schema 与 Java 侧预期列名/类型是否一致 */
    public void validateSchema(TableSchema actual,
                               Map<String, String> expected,
                               String tableName) {
        Map<String, String> actualMap = new LinkedHashMap<>();
        actual.getColumns().forEach(col ->
                actualMap.put(col.getColumnName(), col.getOriginalTypeName()));

        List<String> missing = new ArrayList<>();
        List<String> typeMismatch = new ArrayList<>();

        expected.forEach((name, expectedType) -> {
            if (!actualMap.containsKey(name)) {
                missing.add(name);
            } else {
                String actualType = actualMap.get(name);
                if (!actualType.equalsIgnoreCase(expectedType)) {
                    typeMismatch.add(String.format("  %s: 期望 %s, 实际 %s",
                            name, expectedType, actualType));
                }
            }
        });

        if (!missing.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "表 %s.%s 缺失必要列 %s（请先建表：CREATE TABLE ...）",
                    database, tableName, missing));
        }
        if (!typeMismatch.isEmpty()) {
            log.warn("表 {}.{} 以下列类型与预期不同，运行时可能出错：\n{}",
                    database, tableName, String.join("\n", typeMismatch));
        } else {
            log.info("表 {}.{} schema 校验通过", database, tableName);
        }
    }

    /** parseData 表的预期 schema（只校验必要列） */
    public static final Map<String, String> EXPECTED_PARSE_DATA_COLS = Map.of(
            "srcMac", "String",
            "dstMac", "String",
            "srcAddr", "String",
            "dstAddr", "String",
            "srcPort", "UInt16",
            "dstPort", "UInt16",
            "type", "String",
            "data", "String",
            "time", "DateTime64(6)",
            "source_file", "String"
    );

    /** processed_files 表的预期 schema */
    public static final Map<String, String> EXPECTED_META_COLS = Map.of(
            "file_path", "String",
            "file_size", "UInt64",
            "file_mtime", "DateTime64(6)",
            "status", "String",
            "retry_count", "UInt32",
            "packet_count", "UInt64",
            "updated_at", "DateTime64(6)"
    );

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
