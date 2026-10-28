package com.liuliu.example.decode.store;

import com.clickhouse.client.api.data_formats.RowBinaryFormatWriter;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import com.liuliu.example.decode.model.FileMeta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MetaStore {

    private final ClickHouseClient ch;
    private final TableSchema schema;
    private final InsertSettings insertSettings;

    @Value("${ch.metaTable}")
    private String metaTable;

    // retry.max 的唯一配置入口，所有需要这个值的地方都读这里
    @Value("${retry.max:5}")
    private int retryMax;

    public MetaStore(ClickHouseClient ch, TableSchema metaTableSchema) {
        this.ch = ch;
        this.schema = metaTableSchema;
        // 元数据表每次只写一行，buffer 几 KB 足够
        this.insertSettings = new InsertSettings()
                .setInputStreamCopyBufferSize(4096);
    }

    /** 查询某文件最新状态，无记录返回 null */
    public FileMeta findLatest(String filePath) throws Exception {
        String sql = "SELECT file_size, file_mtime, status, retry_count, packet_count, last_error "
                + "FROM " + metaTable + " FINAL "
                + "WHERE file_path = '" + ClickHouseClient.escape(filePath) + "' LIMIT 1";

        List<GenericRecord> rows = ch.raw().queryAll(sql);
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        GenericRecord row = rows.get(0);
        FileMeta m = new FileMeta();
        m.filePath = filePath;
        m.fileSize = row.getLong("file_size");
        m.fileMtime = toLocalDateTime(row.getObject("file_mtime"));
        m.status = row.getString("status");
        m.retryCount = row.getLong("retry_count");
        m.packetCount = row.getLong("packet_count");
        String err = row.getString("last_error");
        m.lastError = err == null ? "" : err;
        return m;
    }

    /** 把 ClickHouse 返回的时间对象兼容转成 LocalDateTime */
    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.time.OffsetDateTime odt) return odt.toLocalDateTime();
        if (v instanceof java.time.ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (v instanceof java.time.Instant ins) {
            return LocalDateTime.ofInstant(ins, java.time.ZoneId.systemDefault());
        }
        return LocalDateTime.parse(v.toString().replace(" ", "T"));
    }

    public void markProcessing(String filePath, long size, LocalDateTime mtime) throws Exception {
        insert(filePath, size, mtime, "processing", 0, 0, "", LocalDateTime.now());
    }

    public void markDone(String filePath, long size, LocalDateTime mtime, long packetCount) throws Exception {
        insert(filePath, size, mtime, "done", 0, packetCount, "", LocalDateTime.now());
    }

    public void markFailed(String filePath, long size, LocalDateTime mtime,
                           long retryCount, String error) throws Exception {
        String status = retryCount >= retryMax ? "permanently_failed" : "failed";
        insert(filePath, size, mtime, status, retryCount, 0,
                error == null ? "" : error, LocalDateTime.now());
    }

    public Set<String> listDonePaths() throws Exception {
        String sql = "SELECT DISTINCT file_path FROM " + metaTable
                + " FINAL WHERE status = 'done'";
        List<GenericRecord> rows = ch.raw().queryAll(sql);
        Set<String> r = new HashSet<>();
        if (rows != null) {
            for (GenericRecord row : rows) {
                r.add(row.getString("file_path"));
            }
        }
        return r;
    }

    /** 把 permanently_failed 翻回 failed、retry_count 归 0，让下一轮重试一次 */
    public void resetPermanentlyFailed(String filePath) throws Exception {
        FileMeta m = findLatest(filePath);
        if (m == null) return;
        insert(filePath, m.fileSize, m.fileMtime, "failed", 0, 0,
                "manually reset permanently_failed", LocalDateTime.now());
    }

    public int getRetryMax() {
        return retryMax;
    }

    /**
     * 写入前先 DELETE 同 file_path 旧行。
     * ReplacingMergeTree 只靠 FINAL 取最新 updated_at，高频更新时 FINAL 扫全表会变慢；
     * 先 DELETE 旧行让每轮只保留一个版本，FINAL 查询更快、存储更省。
     */
    private void insert(String filePath, long size, LocalDateTime mtime,
                        String status, long retryCount, long packetCount,
                        String lastError, LocalDateTime updatedAt) throws Exception {

        String delSql = "DELETE FROM " + metaTable
                + " WHERE file_path = '" + ClickHouseClient.escape(filePath) + "'";
        ch.execute(delSql);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        RowBinaryFormatWriter writer = new RowBinaryFormatWriter(
                buffer, schema, ClickHouseFormat.RowBinaryWithDefaults);

        writer.setValue("file_path", filePath);
        writer.setValue("file_size", size);
        writer.setValue("file_mtime", mtime.truncatedTo(ChronoUnit.MICROS));
        writer.setValue("status", status);
        writer.setValue("retry_count", (int) retryCount);
        writer.setValue("packet_count", packetCount);
        writer.setValue("last_error", lastError);
        writer.setValue("updated_at", updatedAt.truncatedTo(ChronoUnit.MICROS));
        writer.commitRow();

        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
        try (InsertResponse ignored = ch.raw().insert(
                metaTable, in, ClickHouseFormat.RowBinaryWithDefaults, insertSettings).get()) {
        }
    }
}
