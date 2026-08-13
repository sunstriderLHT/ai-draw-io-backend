package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class QuotaReservationRecoveryJob {

    private final IAiQuotaService quotaService;
    private final Clock clock;
    private final Duration reservationTimeout;

    public QuotaReservationRecoveryJob(
            IAiQuotaService quotaService,
            Clock clock,
            @Value("${ai.quota.reservation-timeout:PT10M}")
            Duration reservationTimeout
    ) {
        this.quotaService = quotaService;
        this.clock = clock;
        this.reservationTimeout = reservationTimeout;
    }

    @Scheduled(
            fixedDelayString =
                    "${ai.quota.recovery-interval:PT1M}"
    )
    public void releaseExpiredReservations() {
        Instant cutoff =
                clock.instant().minus(reservationTimeout);

        int released = quotaService.releaseExpired(cutoff);

        if (released > 0) {
            log.info(
                    "已释放过期额度预占，数量：{}，截止时间：{}",
                    released,
                    cutoff
            );
        }
    }
}
