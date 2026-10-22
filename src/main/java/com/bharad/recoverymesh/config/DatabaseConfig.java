package com.bharad.recoverymesh.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {
    @Bean(name = "primaryDataSource")
    DataSource primaryDataSource(@Value("${recoverymesh.primary.url}") String url,
                                 @Value("${recoverymesh.primary.username}") String username,
                                 @Value("${recoverymesh.primary.password}") String password) {
        return build(url, username, password, "recoverymesh-primary");
    }

    @Bean(name = "secondaryDataSource")
    DataSource secondaryDataSource(@Value("${recoverymesh.secondary.url}") String url,
                                   @Value("${recoverymesh.secondary.username}") String username,
                                   @Value("${recoverymesh.secondary.password}") String password) {
        return build(url, username, password, "recoverymesh-secondary");
    }

    @Bean(name = "primaryJdbc")
    JdbcTemplate primaryJdbc(@Qualifier("primaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "secondaryJdbc")
    JdbcTemplate secondaryJdbc(@Qualifier("secondaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private HikariDataSource build(String url, String username, String password, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1500);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName(poolName);
        return new HikariDataSource(config);
    }
}
