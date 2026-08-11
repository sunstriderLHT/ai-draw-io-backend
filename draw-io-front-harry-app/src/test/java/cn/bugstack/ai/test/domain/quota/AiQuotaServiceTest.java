package cn.bugstack.ai.test.domain.quota;

import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.exception.QuotaExhaustedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestAlreadyCompletedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestInProgressException;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshot;
import cn.bugstack.ai.domain.quota.service.AiQuotaService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Test;


import java.time.Instant;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AiQuotaServiceTest {

    private static final String REQUEST_ID =
            "33333333-3333-3333-3333-333333333333";
    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private final IAiQuotaRepository repository =
            mock(IAiQuotaRepository.class);

    private final AiQuotaService service =
            new AiQuotaService(repository, 3);

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonUuidUserId() {
        service.reserve(
                "not-a-uuid",
                REQUEST_ID,
                "100001",
                "chat"
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonUuidRequestId() {
        service.reserve(
                USER_ID,
                "not-a-uuid",
                "100001",
                "chat"
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsupportedEndpoint() {
        service.reserve(
                USER_ID,
                REQUEST_ID,
                "100001",
                "query_ai_agent_config_list"
        );
    }

    @Test
    public void shouldDelegateWithThreeFreeUses() {
        service.reserve(
                USER_ID,
                REQUEST_ID,
                "100001",
                "chat_stream"
        );

        verify(repository).reserve(
                USER_ID,
                REQUEST_ID,
                "100001",
                "chat_stream",
                3
        );
    }

    @Test
    public void shouldCommitReservedQuota() {
        QuotaSnapshot expected =
                new QuotaSnapshot(3, 0, 1, 0);

        when(repository.commit(USER_ID, REQUEST_ID))
                .thenReturn(expected);

        QuotaSnapshot actual =
                service.commit(USER_ID, REQUEST_ID);

        assertSame(expected, actual);
    }

    @Test
    public void shouldReleaseReservedQuota() {
        QuotaSnapshot expected =
                new QuotaSnapshot(3, 0, 0, 0);

        when(repository.release(USER_ID, REQUEST_ID))
                .thenReturn(expected);

        QuotaSnapshot actual =
                service.release(USER_ID, REQUEST_ID);

        assertSame(expected, actual);
    }

    @Test
    public void shouldGetOrCreateQuotaSnapshot() {
        QuotaSnapshot expected =
                new QuotaSnapshot(3, 0, 0, 0);

        when(repository.findOrCreate(USER_ID, 3))
                .thenReturn(expected);

        QuotaSnapshot actual =
                service.getSnapshot(USER_ID);

        assertSame(expected, actual);
    }

    @Test
    public void shouldReleaseExpiredReservations() {
        Instant cutoff =
                Instant.parse("2026-08-11T00:00:00Z");

        when(repository.releaseExpired(cutoff))
                .thenReturn(2);

        int released = service.releaseExpired(cutoff);

        assertEquals(2, released);
    }

    @Test
    public void shouldExposeLockedSnapshotWhenQuotaIsExhausted() {
        QuotaSnapshot snapshot =
                new QuotaSnapshot(3, 0, 3, 0);

        QuotaExhaustedException error =
                new QuotaExhaustedException(snapshot);

        assertSame(snapshot, error.getSnapshot());
        assertEquals(0, error.getSnapshot().remaining());
    }

    @Test
    public void shouldRepresentRequestInProgressAsAppException() {
        AppException error =
                new QuotaRequestInProgressException();

        assertEquals("0005", error.getCode());
        assertEquals("相同请求正在处理中", error.getInfo());
    }

    @Test
    public void shouldRepresentCompletedRequestAsAppException() {
        AppException error =
                new QuotaRequestAlreadyCompletedException();

        assertEquals("0006", error.getCode());
        assertEquals("相同请求已处理完成", error.getInfo());
    }

}
