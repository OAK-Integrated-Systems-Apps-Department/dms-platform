package com.oak.dms.watermark;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.folder.FolderService;
import com.oak.dms.security.auth.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOC-14 against the real {@link FolderService} and PostgreSQL: structure changes of one tenant's folder tree run one at
 * a time, the second validating against what the first committed.
 * <p>
 * <b>How the race is held open without sleeps.</b> The test holds a row lock (FOR UPDATE) on the row the first operation
 * will write. That operation takes the tenant's advisory lock, reads the tree, passes its checks - and then waits for
 * the row. While it waits it still holds the advisory lock, so the second operation - started now - must queue on the
 * advisory lock before it reads anything; the test sees it waiting in {@code pg_locks}, then releases the row. Without
 * the lock the second operation would run its checks against the old tree right now (the DOC-14 investigation
 * showed exactly that), so "the second one is waiting" is itself the assertion that the lock is taken first.
 */
@SpringBootTest
class FolderTreeConcurrencyIT extends WatermarkFixtures {

    @Autowired private FolderService service;
    @Autowired private FolderRepository repository;
    @Autowired private DataSource dataSource;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    /** Row locks the test holds; always let go in the AfterEach, so a failed test cannot leave the rows locked. */
    private final List<Connection> heldConnections = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach
    void noAdvisoryLockSurvives() throws Exception {
        for (Connection c : heldConnections) {
            try {
                c.rollback();
                c.close();
            } catch (Exception ignored) {
                // already released
            }
        }
        pool.shutdownNow();
        for (int i = 0; i < 50; i++) {
            if (advisoryLocks(true) == 0) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(advisoryLocks(true)).as("advisory locks still held after the test (all sessions)").isZero();
    }

    // ------------------------------------------------------------------ helpers

    private UUID folder(Fixture f, String name, UUID parent) {
        return repository.saveAndFlush(Folder.builder().tenantId(f.tenantId()).parentFolderId(parent).folderName(name)
                .createdBy(f.userId()).build()).getFolderId();
    }

    private <T> Future<T> asUser(Fixture f, Callable<T> body) {
        return pool.submit(() -> {
            TenantContext.set(f.tenantId(), f.userId());
            try {
                return body.call();
            } finally {
                TenantContext.clear();
            }
        });
    }

    /** Throws the failure the operation ended with, unwrapped; null when it succeeded. */
    private Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static String code(Throwable t) {
        return t instanceof ApiException a ? a.getCode() : t.getClass().getSimpleName();
    }

    /** A separate session that holds row locks until released, so the operation that needs them stops at its write. */
    private Connection holdRows(UUID... ids) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        StringBuilder in = new StringBuilder();
        for (UUID id : ids) {
            in.append(in.length() == 0 ? "" : ",").append("'").append(id).append("'");
        }
        try (Statement st = c.createStatement()) {
            st.execute("select 1 from folders where folder_id in (" + in + ") for update");
        }
        heldConnections.add(c);
        return c;
    }

    private void release(Connection c) throws Exception {
        heldConnections.remove(c);
        c.commit();
        c.close();
    }

    private void await(String what, String sql) throws Exception {
        for (int i = 0; i < 200; i++) {
            Integer n = jdbc.queryForObject(sql, Integer.class);
            if (n != null && n >= 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    /** The first operation has passed its checks and is waiting to write (it holds the advisory lock). */
    private void awaitWriterWaiting() throws Exception {
        await("an operation waiting for a row it must write", "select count(*) from pg_stat_activity where "
                + "datname = current_database() and wait_event_type = 'Lock' and wait_event <> 'advisory' "
                + "and (query like 'update folders%' or query like 'insert into folders%')");
    }

    private void awaitAdvisoryWaiter() throws Exception {
        await("an operation waiting for the tenant's advisory lock", "select count(*) from pg_locks where "
                + "database = (select oid from pg_database where datname = current_database()) and locktype = 'advisory' and not granted");
    }

    private int advisoryLocks(boolean grantedOnly) {
        return jdbc.queryForObject("select count(*) from pg_locks where database = (select oid from pg_database where datname = current_database()) and locktype = 'advisory'"
                + (grantedOnly ? " and granted" : ""), Integer.class);
    }

    private String parentOf(UUID id) {
        return jdbc.queryForObject("select coalesce((select p.folder_name from folders p where p.folder_id = f.parent_folder_id), "
                + "'ROOT') from folders f where folder_id = ?", String.class, id);
    }

    private int depthOf(UUID id) {
        return jdbc.queryForObject("with recursive up as (select folder_id, parent_folder_id, 1 as d from folders where folder_id = ? "
                + "union all select f.folder_id, f.parent_folder_id, up.d + 1 from folders f join up on f.folder_id = up.parent_folder_id) "
                + "select max(d) from up", Integer.class, id);
    }

    private int reachableFromTheTopLevel(Fixture f) {
        return jdbc.queryForObject("with recursive t as (select folder_id from folders where tenant_id = ? and parent_folder_id is null "
                + "and not is_deleted union select c.folder_id from folders c join t on c.parent_folder_id = t.folder_id "
                + "where not c.is_deleted) select count(*) from t", Integer.class, f.tenantId());
    }

    // ------------------------------------------------------------------ A: opposite moves

    @Test
    void oppositeMovesDoNotBothSucceedAndNoCycleIsCreated() throws Exception {
        Fixture f = fixture();
        UUID a = folder(f, "A", null);
        UUID b = folder(f, "B", null);
        Connection held = holdRows(a);
        Future<?> first = asUser(f, () -> service.move(a, b));      // A under B: passes its checks, waits to write A
        awaitWriterWaiting();
        Future<?> second = asUser(f, () -> service.move(b, a));     // B under A: must queue before reading anything
        awaitAdvisoryWaiter();
        release(held);

        assertThat(failureOf(first)).isNull();
        Throwable refused = failureOf(second);
        assertThat(refused).isNotNull();
        assertThat(code(refused)).isEqualTo("FOLDER_MOVE_INTO_ITSELF");
        assertThat(parentOf(a)).isEqualTo("B");
        assertThat(parentOf(b)).isEqualTo("ROOT");
        assertThat(reachableFromTheTopLevel(f)).as("both folders are still reachable from the top level").isEqualTo(2);
    }

    // ------------------------------------------------------------------ B: depth

    @Test
    void twoMovesThatTogetherWouldExceedTheDepthLimitAreNotBothApplied() throws Exception {
        Fixture f = fixture();
        UUID q = null;
        for (int i = 1; i <= 15; i++) {
            q = folder(f, "r" + i, q);                                       // r15 is at depth 15
        }
        UUID target = q;
        UUID p = folder(f, "p", null);
        UUID p4 = folder(f, "p4", folder(f, "p3", folder(f, "p2", folder(f, "p1", p))));   // p..p4: 5 deep
        UUID z = folder(f, "z", null);
        Connection held = holdRows(p);
        Future<?> first = asUser(f, () -> service.move(p, target));         // 15 + 4 below p = 19: allowed
        awaitWriterWaiting();
        Future<?> second = asUser(f, () -> service.move(z, p4));            // allowed alone; with the first it would be 21
        awaitAdvisoryWaiter();
        release(held);

        assertThat(failureOf(first)).isNull();
        Throwable refused = failureOf(second);
        assertThat(refused).isNotNull();
        assertThat(code(refused)).isEqualTo("FOLDER_TOO_DEEP");
        assertThat(depthOf(z)).isEqualTo(1);
        assertThat(depthOf(p4)).isLessThanOrEqualTo(20);
    }

    // ------------------------------------------------------------------ C: create versus delete

    @Test
    void aChildCreatedWhileItsParentIsBeingDeletedNeverEndsUpLiveUnderADeletedParent_deleteFirst() throws Exception {
        Fixture f = fixture();
        UUID parent = folder(f, "Parent", null);
        Connection held = holdRows(parent);
        Future<?> delete = asUser(f, () -> { service.delete(parent); return null; });   // checked "no children", waits to write
        awaitWriterWaiting();
        Future<?> create = asUser(f, () -> service.create("Child", parent));
        awaitAdvisoryWaiter();
        release(held);

        assertThat(failureOf(delete)).isNull();
        Throwable refused = failureOf(create);
        assertThat(refused).as("the create finds the parent deleted").isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from folders where parent_folder_id = ? and not is_deleted",
                Integer.class, parent)).isZero();
    }

    @Test
    void aChildCreatedWhileItsParentIsBeingDeletedNeverEndsUpLiveUnderADeletedParent_createFirst() throws Exception {
        Fixture f = fixture();
        UUID parent = folder(f, "Parent", null);
        Connection held = holdRows(parent);
        Future<?> create = asUser(f, () -> service.create("Child", parent));             // checked the parent, waits to insert
        awaitWriterWaiting();
        Future<?> delete = asUser(f, () -> { service.delete(parent); return null; });
        awaitAdvisoryWaiter();
        release(held);

        assertThat(failureOf(create)).isNull();
        Throwable refused = failureOf(delete);
        assertThat(refused).isNotNull();
        assertThat(code(refused)).isEqualTo("FOLDER_NOT_EMPTY");
        assertThat(jdbc.queryForObject("select is_deleted from folders where folder_id = ?", Boolean.class, parent)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from folders where parent_folder_id = ? and not is_deleted",
                Integer.class, parent)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ D: an existing cycle

    @Test
    void theSubtreeQueryTerminatesOnACycleAndListsEachFolderOnce() throws Exception {
        Fixture f = fixture();
        UUID a = folder(f, "A", null);
        UUID b = folder(f, "B", a);
        UUID c = folder(f, "C", b);
        UUID tail = folder(f, "Tail", c);
        jdbc.update("update folders set parent_folder_id = ? where folder_id = ?", c, a);       // A under C: A -> B -> C -> A
        UUID outside = folder(f, "Outside", null);

        List<UUID> ids = transactions.execute(status -> {
            jdbc.execute("set local statement_timeout = 5000");        // a safety net: the old query ran until cancelled
            return repository.findSubtreeIds(f.tenantId(), a);
        });

        assertThat(ids).containsExactlyInAnyOrder(a, b, c, tail).doesNotHaveDuplicates().doesNotContain(outside);
    }

    @Test
    void theSubtreeQueryStillReturnsTheSubtreeOfAValidTreeAndIgnoresDeletedAndOtherTenants() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        UUID root = folder(f, "Root", null);
        UUID x = folder(f, "X", root);
        UUID y = folder(f, "Y", x);
        UUID z = folder(f, "Z", root);
        UUID gone = folder(f, "Gone", root);
        jdbc.update("update folders set is_deleted = true where folder_id = ?", gone);
        UUID foreign = folder(other, "Foreign", null);

        assertThat(repository.findSubtreeIds(f.tenantId(), root)).containsExactlyInAnyOrder(root, x, y, z);
        assertThat(repository.findSubtreeIds(f.tenantId(), x)).containsExactlyInAnyOrder(x, y);
        assertThat(repository.findSubtreeIds(f.tenantId(), foreign)).as("another tenant's folder").isEmpty();
    }

    // ------------------------------------------------------------------ E: tenants do not wait for each other

    @Test
    void anotherTenantIsNotHeldUpByAnOperationInsideItsCriticalSection() throws Exception {
        Fixture one = fixture();
        Fixture two = fixture();
        UUID a = folder(one, "A", null);
        UUID b = folder(one, "B", null);
        Connection held = holdRows(a);
        Future<?> first = asUser(one, () -> service.move(a, b));
        awaitWriterWaiting();                                          // tenant one holds its lock and waits on a row

        Future<?> other = asUser(two, () -> service.create("Mine", null));
        assertThat(other.get(15, TimeUnit.SECONDS)).as("tenant two's create finished while tenant one is still inside")
                .isNotNull();
        assertThat(first.isDone()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from pg_locks where database = (select oid from pg_database where datname = current_database()) and locktype = 'advisory' and not granted",
                Integer.class)).as("nobody queued on tenant two's behalf").isZero();
        release(held);

        assertThat(failureOf(first)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from folders where tenant_id = ? and not is_deleted",
                Integer.class, two.tenantId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ F, G: rollback and failed validation release the lock

    @Test
    void aRollbackReleasesTheLockAndNothingIsHeldOnTheSession() throws Exception {
        Fixture f = fixture();
        String key = FolderServiceKey.of(f.tenantId());
        int[] pid = new int[1];
        try {
            transactions.executeWithoutResult(status -> {
                repository.lockHierarchy(key);
                pid[0] = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertThat(jdbc.queryForObject("select count(*) from pg_locks where database = (select oid from pg_database where datname = current_database()) and locktype = 'advisory' and granted and pid = ?",
                        Integer.class, pid[0])).as("held inside the transaction").isEqualTo(1);
                throw new IllegalStateException("force a rollback");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        assertThat(advisoryLocks(true)).isZero();
        // and it can be taken again at once, by the same pooled connection or another
        Future<Long> again = pool.submit(() -> transactions.execute(status -> repository.lockHierarchy(key)));
        assertThat(again.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        assertThat(advisoryLocks(true)).isZero();
    }

    @Test
    void aFailedValidationAfterTheLockLeavesNoLockAndTheNextOperationProceeds() throws Exception {
        Fixture f = fixture();
        folder(f, "Same", null);

        Throwable refused = failureOf(asUser(f, () -> service.create("same", null)));        // name taken, after the lock
        assertThat(refused).isNotNull();
        assertThat(code(refused)).isEqualTo("FOLDER_NAME_TAKEN");
        assertThat(advisoryLocks(true)).isZero();

        assertThat(asUser(f, () -> service.create("Other", null)).get(15, TimeUnit.SECONDS)).isNotNull();
        assertThat(advisoryLocks(true)).isZero();
    }

    // ------------------------------------------------------------------ H: another application instance

    @Test
    void aLockHeldByAnotherDatabaseSessionBlocksTheServiceLikeAnotherInstanceWould() throws Exception {
        Fixture f = fixture();
        UUID a = folder(f, "A", null);
        UUID b = folder(f, "B", null);
        String key = FolderServiceKey.of(f.tenantId());
        HikariDataSource hikari = (HikariDataSource) dataSource;
        Future<?> move;
        try (Connection instance2 = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword())) {
            instance2.setAutoCommit(false);
            try (Statement st = instance2.createStatement()) {
                st.execute("select pg_advisory_xact_lock(hashtextextended('" + key + "', 0))");   // the other instance, mid-change
            }
            move = asUser(f, () -> service.move(a, b));
            awaitAdvisoryWaiter();
            assertThat(move.isDone()).as("waits for the other instance, not for a JVM-local mutex").isFalse();
            instance2.commit();
        }

        assertThat(failureOf(move)).isNull();
        assertThat(parentOf(a)).isEqualTo("B");
    }

    // ------------------------------------------------------------------ connection use

    @Test
    void noConnectionIsRetainedAfterTheOperationsAreDone() throws Exception {
        Fixture f = fixture();
        UUID a = folder(f, "A", null);
        UUID b = folder(f, "B", null);
        asUser(f, () -> service.move(a, b)).get(15, TimeUnit.SECONDS);
        asUser(f, () -> service.create("C", null)).get(15, TimeUnit.SECONDS);
        asUser(f, () -> { service.delete(a); return null; }).get(15, TimeUnit.SECONDS);   // A is empty

        for (int i = 0; i < 50 && ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections() > 0; i++) {
            Thread.sleep(100);
        }
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and state = 'idle in transaction'", Integer.class)).isZero();
    }

    /** The service's key derivation, from the outside: what the lock is called is part of the contract with other sessions. */
    private static final class FolderServiceKey {
        static String of(UUID tenantId) {
            return "folder-tree:" + tenantId;
        }
    }
}
