package com.zengbohan.aurora.order.config;

import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.id.jdbc.JdbcSegmentLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class IdGeneratorConfig {

    /** Order ids come from the handwritten segment allocator (ADR-0008). */
    @Bean
    public SegmentIdGenerator orderIdGenerator(DataSource dataSource) {
        return new SegmentIdGenerator("order", new JdbcSegmentLoader(dataSource, "aurora_id.leaf_alloc"));
    }
}
