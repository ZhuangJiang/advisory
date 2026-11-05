package com.advisory.support;

import io.minio.BucketExistsArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证共享测试基座本身：容器化的 PostgreSQL 已被 Flyway 迁移过、MinIO 可连通且
 * {@code advisory} 桶存在、{@link IntegrationTestBase#mockMvc} 已被注入。
 */
class IntegrationTestBaseTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migratesSchemaOnContainerizedPostgres() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public'",
                Integer.class);

        assertThat(tables).isGreaterThanOrEqualTo(13);
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void createsMinioBucket() throws Exception {
        boolean exists = minioClient()
                .bucketExists(BucketExistsArgs.builder().bucket(MINIO_BUCKET).build());

        assertThat(exists).isTrue();
    }
}
