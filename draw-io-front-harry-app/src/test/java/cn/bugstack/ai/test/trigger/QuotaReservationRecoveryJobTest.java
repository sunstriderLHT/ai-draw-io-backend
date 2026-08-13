package cn.bugstack.ai.test.trigger;

import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.trigger.job.QuotaReservationRecoveryJob;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class QuotaReservationRecoveryJobTest {

    @Mock
    private IAiQuotaService quotaService;

    @Test
    public void shouldReleaseReservationsOlderThanConfiguredTimeout() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-11T00:20:00Z"),
                ZoneOffset.UTC
        );

        QuotaReservationRecoveryJob job =
                new QuotaReservationRecoveryJob(
                        quotaService,
                        clock,
                        Duration.ofMinutes(10)
                );

        job.releaseExpiredReservations();

        verify(quotaService).releaseExpired(
                Instant.parse("2026-08-11T00:10:00Z")
        );
    }
}
