package io.github.gmshield.jobs.data;

import io.github.gmshield.shared.service.StartupSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code job_probe} (test mode's migration location only) through {@link JdbcClient}, joining the caller's
 * transaction. The client is looked up on first use.
 */
@Repository
@Profile(StartupSettings.TEST_MODE_PROFILE)
class JdbcProbeJobStore implements ProbeJobStore {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcProbeJobStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProbeJobRow row) {
        jdbc.getObject().sql("insert into job_probe (job_id, work_ms, fail_times) values (:job, :work, :fail)")
                .param("job", row.jobId())
                .param("work", row.workMs())
                .param("fail", row.failTimes())
                .update();
    }

    @Override
    public Optional<ProbeJobRow> find(UUID jobId) {
        return jdbc.getObject().sql("select job_id, work_ms, fail_times from job_probe where job_id = :job")
                .param("job", jobId)
                .query((rs, n) -> new ProbeJobRow(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)))
                .optional();
    }
}
