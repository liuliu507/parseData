package com.liuliu.example.decode.store;

import com.clickhouse.client.api.data_formats.RowBinaryFormatWriter;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.data.ClickHouseFormat;
import com.liuliu.example.decode.model.PacketInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class DataWriter {

    private final ClickHouseClient ch;
    private final TableSchema schema;
    private final InsertSettings insertSettings;
    private final int preallocateBytes;

    @Value("${ch.table}")
    private String table;

    public DataWriter(ClickHouseClient ch, TableSchema parseDataSchema,
                      @Value("${batch.size:500}") int batchSize) {
        this.ch = ch;
        this.schema = parseDataSchema;

        // 预分配 buffer：每条 UDP 帧 hex 编码后约 3KB，batchSize 条估算
        this.preallocateBytes = batchSize * 3072;
        this.insertSettings = new InsertSettings()
                .setInputStreamCopyBufferSize(Math.max(preallocateBytes, 65_500));
    }

    public void writeBatch(List<PacketInfo> batch, String sourceFile) throws Exception {
        if (batch.isEmpty()) return;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(preallocateBytes);
        RowBinaryFormatWriter writer = new RowBinaryFormatWriter(
                buffer, schema, ClickHouseFormat.RowBinaryWithDefaults);

        for (PacketInfo p : batch) {
            writer.setValue("srcMac", p.srcMac);
            writer.setValue("dstMac", p.dstMac);
            writer.setValue("srcAddr", p.srcAddr);
            writer.setValue("dstAddr", p.dstAddr);
            writer.setValue("srcPort", p.srcPort);
            writer.setValue("dstPort", p.dstPort);
            writer.setValue("type", p.type);
            writer.setValue("data", p.data);
            writer.setValue("time", p.time);
            writer.setValue("source_file", sourceFile);
            writer.commitRow();
        }

        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
        try (InsertResponse ignored = ch.raw().insert(
                table, in, ClickHouseFormat.RowBinaryWithDefaults, insertSettings).get()) {
        }
    }
}
