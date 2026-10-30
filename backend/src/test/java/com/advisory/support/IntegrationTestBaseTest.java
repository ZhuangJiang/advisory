package com.advisory.support;

import io.minio.BucketExistsArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the shared test base itself: containerized PostgreSQL is migrated by Flyway, MinIO is
 * reachable and the {@code advisory} bucket exists, and {@link IntegrationTestBase#mockMvc} is
 * injected.
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
