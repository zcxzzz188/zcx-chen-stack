package com.zcx.chenstack.config;

import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.wiki.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Explicit opt-in; schema/trigger readiness is checked, never automatically migrated. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chen.wiki", name = {"enabled", "product.enabled"}, havingValue = "true")
public class WikiProductConfiguration {
    @Bean
    public WikiPublicationService wikiPublicationService(DataSource dataSource, PlatformTransactionManager manager,
                                                         WikiFoundation foundation, AdminSecurity security) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        return new WikiPublicationService(jdbc, manager, foundation, security);
    }

    @Bean
    public WikiManagementService wikiManagementService(DataSource dataSource, WikiFoundation foundation,
                                                       WikiPublicationService publication, ObjectProvider<WikiCompilationService> compiler) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        return new WikiManagementService(jdbc, foundation, publication, compiler);
    }
}
