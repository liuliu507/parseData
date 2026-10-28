package com.liuliu.example.decode.model;

import java.time.LocalDateTime;

public class FileMeta {
    public String filePath;
    public long fileSize;
    public LocalDateTime fileMtime;
    public String status;         // processing / done / failed / permanently_failed
    public long retryCount;
    public long packetCount;
    public String lastError;
    public LocalDateTime updatedAt;

    public boolean sameIdentity(String path, long size, LocalDateTime mtime) {
        return this.filePath.equals(path)
                && this.fileSize == size
                && this.fileMtime.equals(mtime);
    }
}