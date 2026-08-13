package cn.bugstack.ai.test.domain.quota;

import cn.bugstack.ai.domain.quota.exception.QuotaExhaustedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestAlreadyCompletedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestInProgressException;
import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.valobj.QuotaLedgerStatus;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.MySQLContainer;
import org.junit.runner.RunWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.infrastructure.adapter.repository.MySqlAiQuotaRepository;
import cn.bugstack.ai.infrastructure.dao.IAiQuotaLedgerDao;
import cn.bugstack.ai.infrastructure.dao.IAiUserQuotaDao;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RunWith(SpringRunner.class)
@ContextConfiguration(
        classes = MySqlAiQuotaRepositoryTest.TestConfiguration.class
)
public class MySqlAiQuotaRepositoryTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private static final String FREE_GRANT_USER_ID =
            "44444444-4444-4444-4444-444444444444";

    private static final String IDEMPOTENT_USER_ID =
            "55555555-5555-5555-5555-555555555555";

    private static final String RESERVE_USER_ID =
            "66666666-6666-6666-6666-666666666666";

    private static final String RESERVE_REQUEST_ID =
            "77777777-7777-7777-7777-777777777777";

    private static final String COMMIT_USER_ID =
            "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private static final String COMMIT_REQUEST_ID =
            "dddddddd-dddd-dddd-dddd-dddddddddddd";

    private static final String RELEASE_USER_ID =
            "12121212-1212-1212-1212-121212121212";

    private static final String RELEASE_REQUEST_ID =
            "34343434-3434-3434-3434-343434343434";

    @Autowired
    private IAiQuotaRepository repository;

    @ClassRule
    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0.36")
                    .withDatabaseName("drawio_test")
                    .withUsername("drawio")
                    .withPassword("test-only-password")
                    .withCommand(
                            "--log-bin-trust-function-creators=1"
                    );

    @BeforeClass
    public static void migrateDatabase() {
        Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    public void shouldCreateQuotaTables() throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name IN (
                      'ai_user_quota',
                      'ai_quota_ledger'
                  )
                """;

        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, MYSQL.getDatabaseName());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(2, resultSet.getInt(1));
            }
        }
    }

    @Test
    public void shouldCreateNewAccountWithThreeFreeUses() {
        QuotaSnapshotEntity snapshot =
                repository.findOrCreate(USER_ID, 3);

        assertEquals(3, snapshot.freeGranted());
        assertEquals(0, snapshot.purchasedGranted());
        assertEquals(0, snapshot.consumed());
        assertEquals(0, snapshot.reserved());
        assertEquals(3, snapshot.remaining());
    }

    @Test
    public void shouldCreateOneCommittedFreeGrantLedger() throws Exception {

        repository.findOrCreate(FREE_GRANT_USER_ID, 3);
        String sql = """
                SELECT COUNT(*)
                FROM ai_quota_ledger
                WHERE user_id = ?
                  AND request_id = 'FREE_GRANT'
                  AND endpoint = 'system'
                  AND entry_type = 'FREE_GRANT'
                  AND amount = 3
                  AND status = 'COMMITTED'
                """;

        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, FREE_GRANT_USER_ID);

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    @Test
    public void shouldNotGrantFreeQuotaTwice() throws Exception {
        QuotaSnapshotEntity first =
                repository.findOrCreate(IDEMPOTENT_USER_ID, 3);

        QuotaSnapshotEntity second =
                repository.findOrCreate(IDEMPOTENT_USER_ID, 3);

        assertEquals(3, first.freeGranted());
        assertEquals(3, second.freeGranted());
        assertEquals(3, second.remaining());

        String sql = """
            SELECT COUNT(*)
            FROM ai_quota_ledger
            WHERE user_id = ?
              AND request_id = 'FREE_GRANT'
              AND entry_type = 'FREE_GRANT'
              AND status = 'COMMITTED'
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setString(1, IDEMPOTENT_USER_ID);

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    @Test
    public void shouldReserveOneUseBeforeModelInvocation() throws Exception {

        QuotaReservationEntity reservation =
                repository.reserve(
                        RESERVE_USER_ID,
                        RESERVE_REQUEST_ID,
                        "100001",
                        "chat",
                        3
                );

        assertNotNull(reservation);
        assertEquals(
                RESERVE_REQUEST_ID,
                reservation.requestId()
        );
        assertEquals(
                QuotaLedgerStatus.RESERVED,
                reservation.status()
        );

        QuotaSnapshotEntity snapshot = reservation.snapshot();

        assertEquals(3, snapshot.freeGranted());
        assertEquals(0, snapshot.purchasedGranted());
        assertEquals(0, snapshot.consumed());
        assertEquals(1, snapshot.reserved());
        assertEquals(2, snapshot.remaining());

        String sql = """
            SELECT COUNT(*)
            FROM ai_quota_ledger
            WHERE user_id = ?
              AND request_id = ?
              AND agent_id = '100001'
              AND endpoint = 'chat'
              AND entry_type = 'CHAT_USAGE'
              AND amount = -1
              AND status = 'RESERVED'
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setString(1, RESERVE_USER_ID);
            statement.setString(2, RESERVE_REQUEST_ID);

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    @Test
    public void shouldRejectFourthReservationWhenFreeQuotaIsExhausted() {
        String userId =
                "88888888-8888-8888-8888-888888888888";

        String[] requestIds = {
                "90000000-0000-0000-0000-000000000001",
                "90000000-0000-0000-0000-000000000002",
                "90000000-0000-0000-0000-000000000003"
        };

        for (int index = 0; index < requestIds.length; index++) {
            QuotaReservationEntity reservation =
                    repository.reserve(
                            userId,
                            requestIds[index],
                            "100001",
                            "chat",
                            3
                    );

            assertEquals(
                    index + 1,
                    reservation.snapshot().reserved()
            );
            assertEquals(
                    2 - index,
                    reservation.snapshot().remaining()
            );
        }

        try {
            repository.reserve(
                    userId,
                    "90000000-0000-0000-0000-000000000004",
                    "100001",
                    "chat",
                    3
            );

            fail("第四次预占应该因额度耗尽而失败");
        } catch (QuotaExhaustedException error) {
            QuotaSnapshotEntity snapshot = error.getSnapshot();

            assertEquals(3, snapshot.freeGranted());
            assertEquals(0, snapshot.consumed());
            assertEquals(3, snapshot.reserved());
            assertEquals(0, snapshot.remaining());
        }

        QuotaSnapshotEntity persisted =
                repository.findOrCreate(userId, 3);

        assertEquals(3, persisted.reserved());
        assertEquals(0, persisted.consumed());
        assertEquals(0, persisted.remaining());
    }

    @Test
    public void shouldRejectDuplicateReservedRequest() {
        String userId =
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        String requestId =
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        QuotaReservationEntity first =
                repository.reserve(
                        userId,
                        requestId,
                        "100001",
                        "chat_stream",
                        3
                );

        assertEquals(1, first.snapshot().reserved());
        assertEquals(2, first.snapshot().remaining());

        try {
            repository.reserve(
                    userId,
                    requestId,
                    "100001",
                    "chat_stream",
                    3
            );

            fail("相同请求仍在处理中，不应再次预占");
        } catch (QuotaRequestInProgressException error) {
            assertEquals("0005", error.getCode());
        }

        QuotaSnapshotEntity persisted =
                repository.findOrCreate(userId, 3);

        assertEquals(1, persisted.reserved());
        assertEquals(0, persisted.consumed());
        assertEquals(2, persisted.remaining());
    }

    @Test
    public void shouldCommitReservedQuotaExactlyOnce() throws Exception {

        QuotaReservationEntity reservation =
                repository.reserve(
                        COMMIT_USER_ID,
                        COMMIT_REQUEST_ID,
                        "100001",
                        "chat",
                        3
                );

        assertEquals(1, reservation.snapshot().reserved());
        assertEquals(0, reservation.snapshot().consumed());

        QuotaSnapshotEntity committed =
                repository.commit(
                        COMMIT_USER_ID,
                        COMMIT_REQUEST_ID
                );

        assertEquals(0, committed.reserved());
        assertEquals(1, committed.consumed());
        assertEquals(2, committed.remaining());

        String sql = """
            SELECT status
            FROM ai_quota_ledger
            WHERE user_id = ?
              AND request_id = ?
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setString(1, COMMIT_USER_ID);
            statement.setString(2, COMMIT_REQUEST_ID);

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                resultSet.next();
                assertEquals(
                        "COMMITTED",
                        resultSet.getString("status")
                );
            }
        }
    }

    @Test
    public void shouldRejectDuplicateCommitWithoutChangingCounters() {
        String userId =
                "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

        String requestId =
                "ffffffff-ffff-ffff-ffff-ffffffffffff";

        repository.reserve(
                userId,
                requestId,
                "100001",
                "chat",
                3
        );

        QuotaSnapshotEntity first =
                repository.commit(userId, requestId);

        assertEquals(1, first.consumed());
        assertEquals(0, first.reserved());
        assertEquals(2, first.remaining());

        try {
            repository.commit(userId, requestId);

            fail("已经提交的请求不能再次扣费");
        } catch (
                QuotaRequestAlreadyCompletedException error
        ) {
            assertEquals("0006", error.getCode());
        }

        QuotaSnapshotEntity persisted =
                repository.findOrCreate(userId, 3);

        assertEquals(1, persisted.consumed());
        assertEquals(0, persisted.reserved());
        assertEquals(2, persisted.remaining());
    }

    @Test
    public void shouldReleaseReservationWithoutConsumingQuota() throws Exception {

        QuotaReservationEntity reservation =
                repository.reserve(
                        RELEASE_USER_ID,
                        RELEASE_REQUEST_ID,
                        "100001",
                        "chat_stream",
                        3
                );

        assertEquals(1, reservation.snapshot().reserved());
        assertEquals(2, reservation.snapshot().remaining());

        QuotaSnapshotEntity released =
                repository.release(
                        RELEASE_USER_ID,
                        RELEASE_REQUEST_ID
                );

        assertEquals(0, released.reserved());
        assertEquals(0, released.consumed());
        assertEquals(3, released.remaining());

        String sql = """
            SELECT status
            FROM ai_quota_ledger
            WHERE user_id = ?
              AND request_id = ?
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {
            statement.setString(1, RELEASE_USER_ID);
            statement.setString(2, RELEASE_REQUEST_ID);

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                resultSet.next();

                assertEquals(
                        "RELEASED",
                        resultSet.getString("status")
                );
            }
        }
    }

    @Test
    public void shouldReleaseSameReservationOnlyOnce() {
        String userId =
                "56565656-5656-5656-5656-565656565656";

        String requestId =
                "78787878-7878-7878-7878-787878787878";

        repository.reserve(
                userId,
                requestId,
                "100001",
                "chat_stream",
                3
        );

        QuotaSnapshotEntity first =
                repository.release(userId, requestId);

        QuotaSnapshotEntity second =
                repository.release(userId, requestId);

        assertEquals(0, first.reserved());
        assertEquals(0, first.consumed());
        assertEquals(3, first.remaining());

        assertEquals(0, second.reserved());
        assertEquals(0, second.consumed());
        assertEquals(3, second.remaining());

        QuotaSnapshotEntity persisted =
                repository.findOrCreate(userId, 3);

        assertEquals(0, persisted.reserved());
        assertEquals(0, persisted.consumed());
        assertEquals(3, persisted.remaining());
    }

    @Test
    public void shouldAllowExactlyThreeOfFourConcurrentReservations() throws Exception {

        String userId =
                "90909090-9090-9090-9090-909090909090";

        String[] requestIds = {
                "91919191-9191-9191-9191-919191919191",
                "92929292-9292-9292-9292-929292929292",
                "93939393-9393-9393-9393-939393939393",
                "94949494-9494-9494-9494-949494949494"
        };

        // 先初始化账户，避免把账户初始化并发混入预占测试。
        repository.findOrCreate(userId, 3);

        ExecutorService executor =
                Executors.newFixedThreadPool(4);

        CountDownLatch ready =
                new CountDownLatch(4);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successes =
                new AtomicInteger();

        AtomicInteger exhausted =
                new AtomicInteger();

        List<Future<?>> futures =
                new ArrayList<>();

        try {
            for (String requestId : requestIds) {
                futures.add(
                        executor.submit(() -> {
                            ready.countDown();

                            try {
                                start.await();

                                repository.reserve(
                                        userId,
                                        requestId,
                                        "100001",
                                        "chat_stream",
                                        3
                                );

                                successes.incrementAndGet();
                            } catch (
                                    QuotaExhaustedException error
                            ) {
                                exhausted.incrementAndGet();
                            }

                            return null;
                        })
                );
            }

            assertEquals(
                    true,
                    ready.await(10, TimeUnit.SECONDS)
            );

            start.countDown();

            for (Future<?> future : futures) {
                // 未预期的异常会在这里重新抛出，让测试失败。
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(3, successes.get());
        assertEquals(1, exhausted.get());

        QuotaSnapshotEntity persisted =
                repository.findOrCreate(userId, 3);

        assertEquals(3, persisted.reserved());
        assertEquals(0, persisted.consumed());
        assertEquals(0, persisted.remaining());
    }

    @Test
    public void shouldReactivateReleasedRequest() {
        String userId =
                "15151515-1515-1515-1515-151515151515";

        String requestId =
                "16161616-1616-1616-1616-161616161616";

        repository.reserve(
                userId,
                requestId,
                "100001",
                "chat_stream",
                3
        );

        QuotaSnapshotEntity released =
                repository.release(userId, requestId);

        assertEquals(0, released.reserved());
        assertEquals(3, released.remaining());

        QuotaReservationEntity retried =
                repository.reserve(
                        userId,
                        requestId,
                        "100001",
                        "chat_stream",
                        3
                );

        assertEquals(
                QuotaLedgerStatus.RESERVED,
                retried.status()
        );
        assertEquals(1, retried.snapshot().reserved());
        assertEquals(0, retried.snapshot().consumed());
        assertEquals(2, retried.snapshot().remaining());
    }

    @Test
    public void shouldReleaseExpiredReservation() throws Exception {
        String userId =
                "17171717-1717-1717-1717-171717171717";

        String requestId =
                "18181818-1818-1818-1818-181818181818";

        repository.reserve(
                userId,
                requestId,
                "100001",
                "chat_stream",
                3
        );
        // 配置cutoff时间为now-30s
        Instant cutoff = Instant.now().minusSeconds(30);

        // 将本条流水事件调整成cutoff之前
        String ageReservationSql = """
            UPDATE ai_quota_ledger
            SET updated_at = ?
            WHERE user_id = ?
              AND request_id = ?
              AND status = 'RESERVED'
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                PreparedStatement statement =
                        connection.prepareStatement(
                                ageReservationSql
                        )
        ) {
            statement.setObject(1, LocalDateTime.ofInstant(cutoff.minusSeconds(60), ZoneOffset.UTC));
            statement.setString(2, userId);
            statement.setString(3, requestId);

            assertEquals(1, statement.executeUpdate());
        }

        int released = repository.releaseExpired(cutoff);

        assertEquals(1, released);

        QuotaSnapshotEntity snapshot =
                repository.findOrCreate(userId, 3);

        assertEquals(0, snapshot.reserved());
        assertEquals(0, snapshot.consumed());
        assertEquals(3, snapshot.remaining());
    }

    @Test
    public void shouldRollbackAccountWhenLedgerCommitFails() throws Exception {
        String userId =
                "19191919-1919-1919-1919-191919191919";

        String requestId =
                "20202020-2020-2020-2020-202020202020";

        repository.reserve(
                userId,
                requestId,
                "100001",
                "chat",
                3
        );

        String triggerName = "fail_quota_ledger_commit";

        String createTriggerSql = """
            CREATE TRIGGER fail_quota_ledger_commit
            BEFORE UPDATE ON ai_quota_ledger
            FOR EACH ROW
            BEGIN
                IF NEW.request_id =
                   '20202020-2020-2020-2020-202020202020'
                   AND NEW.status = 'COMMITTED'
                THEN
                    SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'forced ledger commit failure';
                END IF;
            END
            """;

        try (
                Connection connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(),
                                MYSQL.getUsername(),
                                MYSQL.getPassword()
                        );
                Statement statement =
                        connection.createStatement()
        ) {
            statement.execute(
                    "DROP TRIGGER IF EXISTS " + triggerName
            );
            statement.execute(createTriggerSql);
        }

        try {
            repository.commit(userId, requestId);
            fail("流水提交失败时，整个额度事务应该失败");
        } catch (Exception expected) {
            // MySQL trigger 故意阻止流水状态更新。
        } finally {
            try (
                    Connection connection =
                            DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(),
                                    MYSQL.getUsername(),
                                    MYSQL.getPassword()
                            );
                    Statement statement =
                            connection.createStatement()
            ) {
                statement.execute(
                        "DROP TRIGGER IF EXISTS " + triggerName
                );
            }
        }

        QuotaSnapshotEntity snapshot =
                repository.findOrCreate(userId, 3);

        assertEquals(0, snapshot.consumed());
        assertEquals(1, snapshot.reserved());
        assertEquals(2, snapshot.remaining());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = IAiUserQuotaDao.class)
    static class TestConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mybatis/mapper/ai_*_mapper.xml")
            );

            return factory.getObject();
        }

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource();

            dataSource.setDriverClassName(
                    MYSQL.getDriverClassName()
            );
            dataSource.setUrl(MYSQL.getJdbcUrl());
            dataSource.setUsername(MYSQL.getUsername());
            dataSource.setPassword(MYSQL.getPassword());

            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        IAiQuotaRepository aiQuotaRepository(
                IAiUserQuotaDao userQuotaDao,
                IAiQuotaLedgerDao quotaLedgerDao
        ) {
            return new MySqlAiQuotaRepository(userQuotaDao, quotaLedgerDao);
        }
    }
}
