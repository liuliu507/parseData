package com.liuliu.example.decode.config;

import com.clickhouse.client.api.metadata.TableSchema;
import com.liuliu.example.decode.store.ClickHouseClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickHouseConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseConfig.class);

    @Value("${ch.endpoint}")
    private String endpoint;

    @Value("${ch.username}")
    private String username;

    @Value("${ch.password}")
    private String password;

    @Value("${ch.database}")
    private String database;

    @Value("${ch.table}")
    private String dataTable;

    @Value("${ch.metaTable}")
    private String metaTable;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Bean(destroyMethod = "close")
    public ClickHouseClient clickHouseClient() {
        return new ClickHouseClient(endpoint, username, password, database);
    }

    /** 动态拉 schema 并校验，test profile 下跳过 */
    @Bean
    public TableSchema parseDataSchema(ClickHouseClient ch) throws Exception {
        if ("test".equals(activeProfile)) {
            log.info("test profile 下跳过 parseData schema 校验");
            return buildHardcodedParseDataSchema();
        }
        TableSchema schema = ch.fetchSchema(dataTable);
        ch.validateSchema(schema, ClickHouseClient.EXPECTED_PARSE_DATA_COLS, dataTable);
        return schema;
    }

    @Bean
    public TableSchema metaTableSchema(ClickHouseClient ch) throws Exception {
        if ("test".equals(activeProfile)) {
            log.info("test profile 下跳过 processed_files schema 校验");
            return buildHardcodedMetaSchema();
        }
        TableSchema schema = ch.fetchSchema(metaTable);
        ch.validateSchema(schema, ClickHouseClient.EXPECTED_META_COLS, metaTable);
        return schema;
    }

    private TableSchema buildHardcodedParseDataSchema() {
        TableSchema s = new TableSchema();
        s.addColumn("srcMac", "String");
        s.addColumn("dstMac", "String");
        s.addColumn("srcAddr", "String");
        s.addColumn("dstAddr", "String");
        s.addColumn("srcPort", "UInt16");
        s.addColumn("dstPort", "UInt16");
        s.addColumn("type", "String");
        s.addColumn("data", "String");
        s.addColumn("time", "DateTime64(6)");
        s.addColumn("source_file", "String");
        return s;
    }

    private TableSchema buildHardcodedMetaSchema() {
        TableSchema s = new TableSchema();
        s.addColumn("file_path", "String");
        s.addColumn("file_size", "UInt64");
        s.addColumn("file_mtime", "DateTime64(6)");
        s.addColumn("status", "String");
        s.addColumn("retry_count", "UInt32");
        s.addColumn("packet_count", "UInt64");
        s.addColumn("last_error", "String");
        s.addColumn("updated_at", "DateTime64(6)");
        return s;
    }
}
