package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.wiki.WikiFoundation;
import com.zcx.chenstack.wiki.WikiSourceReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** 默认关闭；不创建 Schema、不导入知识、不注册模型工具或调度任务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chen.wiki", name = "enabled", havingValue = "true")
public class WikiFoundationConfiguration {
    @Bean
    public WikiFoundation wikiFoundation(DataSource dataSource, PlatformTransactionManager transactionManager,
                                         CatalogLifecycle catalog, ObjectMapper mapper, AdminSecurity security) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        return new WikiFoundation(jdbc, transactionManager, new WikiSourceReader(jdbc, catalog, mapper), security);
    }
}

