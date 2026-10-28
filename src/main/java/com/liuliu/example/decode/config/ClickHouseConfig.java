package com.liuliu.example.decode.config;

import com.liuliu.example.decode.store.ClickHouseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickHouseConfig {

    @Value("${ch.endpoint}")
    private String endpoint;

    @Value("${ch.username}")
    private String username;

    @Value("${ch.password}")
    private String password;

    @Value("${ch.database}")
    private String database;

    @Bean(destroyMethod = "close")
    public ClickHouseClient clickHouseClient() {
        return new ClickHouseClient(endpoint, username, password, database);
    }
}