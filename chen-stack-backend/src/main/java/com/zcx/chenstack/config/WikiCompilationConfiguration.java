package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.wiki.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** 双重显式启用并提供独立 WikiModel 才装配；不读取现有模型密钥、不自动迁移或调度。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chen.wiki", name = {"enabled", "compiler.enabled"}, havingValue = "true")
public class WikiCompilationConfiguration {
    @Bean
    public WikiCompilationService wikiCompilationService(WikiFoundation foundation, DataSource dataSource,
                                                        ObjectMapper json, WikiModel wikiModel) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        return new WikiCompilationService(foundation, jdbc, json, wikiModel);
    }
}
