package com.oak.dms.document;

import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.PromotionRefusedException;
import com.oak.dms.scanning.repository.ScanEngineRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOC-06 at promotion level: {@code DocumentVersionPromoter} re-checks the document under its row lock, in the
 * transaction that adds the version, before it writes anything. Real database and the real promoter (through
 * {@code WorkflowFixtures.newVersion}, which does what the ingestion gate does once a file is clean: prepare, then
 * persist in one transaction). A refusal rolls that transaction back, so each test compares a snapshot of the document,
 * its versions, its scan rows and its signatures before and after.
 * <p>
 * The two concurrency tests coordinate real transactions on separate threads with latches and wait for the database
 * itself to report the blocked session ({@code pg_stat_activity}), not for time to pass: one proves the promotion
 * <em>waits</em> for a lock another transaction is applying and then refuses on the committed state; the other that a
 * lock arriving while a promotion is in flight waits for it, so the two serialise.
 */
@SpringBootTest
@Import({WorkflowFixtures.class, DocumentVersionPromotionGuardIT.Hook.class})
class DocumentVersionPromotionGuardIT extends WorkflowApiSupport {

    private static final String REPLACEMENT = "replacement_upload";
    private static final String ROLLBACK = "rollback";
    private static final String SIGNED_COPY = "signed_copy";

    /** Lets a test pause a promotion inside its transaction, after the document row is updated and still locked. */
    @TestConfiguration
    static class Hook {
        static volatile CountDownLatch reached;
        static volatile CountDownLatch proceed;

        @Component
        static class Listener {
            @EventListener
            void onVersionCreated(DocumentVersionCreatedEvent event) throws InterruptedException {
                CountDownLatch arrived = reached;
                CountDownLatch go = proceed;
                if (arrived != null && go != null) {
                    arrived.countDown();
                    if (!go.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the promotion");
                    }
                }
            }
        }
    }

    @Autowired private DocumentRepository documents;
    @Autowired private DocumentVersionPromoter promoter;
    @Autowired private ScanEngineRepository scanEngineRepository;
    @Autowired private ScanResultRepository scanResultRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;
    @Autowired private DocumentVersionService versionService;
    @Autowired private com.oak.dms.storage.service.ObjectStorageServiceFactory storage;

    private UUID tenantId;
    private User alice;
    private UUID documentId;

    @BeforeEach
    void world() {
        Hook.reached = null;
        Hook.proceed = null;
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        documentId = fx.document(tenantId, type, alice, "Master agreement");
    }

    // ------------------------------------------------------------------ the ordinary case

    @Test
    void anOrdinaryDocument_takesANewVersion_asBefore() {
        UUID version = fx.newVersion(tenantId, documentId, alice);
        assertThat(currentVersion()).isEqualTo(version);
        assertThat(latestVersion()).isEqualTo(version);
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void anUnsignedRestore_takesANewVersion_asBefore() {
        UUID version = fx.newVersion(tenantId, documentId, alice, ROLLBACK);
        assertThat(currentVersion()).isEqualTo(version);
    }

    // ------------------------------------------------------------------ refusals - each leaves everything as it was

    @Test
    void aWorkflowSignatureLockAppliedAfterAcceptance_refusesThePromotion() {
        lockToCurrentVersion();
        String before = snapshot();

        assertRefused(REPLACEMENT, "DOCUMENT_SIGNATURE_LOCKED");
        assertRefused(ROLLBACK, "DOCUMENT_SIGNATURE_LOCKED");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aLegalHoldPlacedAfterAcceptance_refusesThePromotion() {
        placeLegalHold();
        String before = snapshot();

        assertRefused(REPLACEMENT, "DOCUMENT_LEGAL_HOLD");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aDocumentDeletedAfterAcceptance_isRefusedExplicitly_notAsAMissingRow() {
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", documentId);
        String before = snapshot();

        assertRefused(REPLACEMENT, "DOCUMENT_UNAVAILABLE");
        assertRefused(SIGNED_COPY, "DOCUMENT_UNAVAILABLE"); // deletion is refused for every reason

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aVersionSignedAfterAcceptance_isNotReplacedByAnUploadOrARestore() {
        markCurrentAsSigned();
        String before = snapshot();

        assertRefused(REPLACEMENT, "DOCUMENT_HAS_SIGNATURE");
        assertRefused(ROLLBACK, "DOCUMENT_HAS_SIGNATURE");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anotherTenantsPromotion_cannotReachThisDocument() {
        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        String before = snapshot();

        assertThatThrownBy(() -> fx.newVersion(otherTenant, documentId, outsider))
                .isInstanceOfSatisfying(PromotionRefusedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_UNAVAILABLE"));

        assertThat(snapshot()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ what must keep working

    @Test
    void signingGeneratedVersions_stillPromote_evenOntoAnAlreadySignedVersion() {
        markCurrentAsSigned(); // a second stamp, an added page: signed_copy on top of signed_copy
        UUID version = fx.newVersion(tenantId, documentId, alice, SIGNED_COPY);
        assertThat(currentVersion()).isEqualTo(version);
    }

    @Test
    void theOneExemption_isSignedCopyOnly_andItAppliesToTheLockAndHoldRulesToo_documentedBehaviour() {
        // FOLLOW-UP noted in DocumentVersionPromoter: the workflow stamping flow has no request-time hold guard, so
        // signed_copy keeps its existing behaviour. Pinned so a change to it is deliberate.
        placeLegalHold();
        UUID version = fx.newVersion(tenantId, documentId, alice, SIGNED_COPY);
        assertThat(currentVersion()).isEqualTo(version);
    }

    // ------------------------------------------------------------------ DOC-07: the check-out, on real persisted state

    @Test
    void anotherUsersActiveCheckOut_refusesAnOrdinaryUpload_andNothingChanges() {
        User bob = fx.user(tenantId, "Bob");
        checkOut(bob.getUserId(), java.time.OffsetDateTime.now().minusHours(1), java.time.OffsetDateTime.now().plusHours(70));
        String before = snapshot();

        assertThatThrownBy(() -> promote(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(5)))
                .isInstanceOfSatisfying(PromotionRefusedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_CHECKED_OUT"));

        assertThat(snapshot()).isEqualTo(before);
        assertThat(checkedOutBy()).as("Bob's check-out is untouched").isEqualTo(bob.getUserId());
    }

    @Test
    void theHoldersOwnPendingCheckIn_promotes_andTheCheckOutIsNotReleasedByThePromotion() {
        java.time.OffsetDateTime taken = java.time.OffsetDateTime.now().minusHours(2);
        checkOut(alice.getUserId(), taken, java.time.OffsetDateTime.now().plusHours(70));

        UUID version = promote(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(30));

        assertThat(currentVersion()).isEqualTo(version);
        assertThat(checkedOutBy()).as("released only by the holder, or at expiry").isEqualTo(alice.getUserId());
    }

    @Test
    void aPendingUploadFollowedByACheckOut_cannotPromoteAcrossIt_whoeverTookIt() {
        User bob = fx.user(tenantId, "Bob");
        java.time.OffsetDateTime submitted = java.time.OffsetDateTime.now().minusMinutes(30);
        for (UUID holder : new UUID[]{bob.getUserId(), alice.getUserId()}) {
            checkOut(holder, java.time.OffsetDateTime.now().minusMinutes(10), java.time.OffsetDateTime.now().plusHours(70));
            String before = snapshot();
            assertThatThrownBy(() -> promote(REPLACEMENT, alice, submitted))
                    .isInstanceOfSatisfying(PromotionRefusedException.class,
                            e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_CHECKED_OUT"));
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void anExpiredCheckInLease_isNotAuthorised_whetherNobodyTookOver_orSomeoneDid() {
        User bob = fx.user(tenantId, "Bob");
        java.time.OffsetDateTime submitted = java.time.OffsetDateTime.now().minusHours(3);

        // the original lease expired and nobody took over: the stale check-in is refused
        checkOut(alice.getUserId(), java.time.OffsetDateTime.now().minusHours(4), java.time.OffsetDateTime.now().minusHours(1));
        String before = snapshot();
        assertThatThrownBy(() -> promote(REPLACEMENT, alice, submitted)).isInstanceOfSatisfying(
                PromotionRefusedException.class, e -> assertThat(e.getCode()).isEqualTo("CHECKOUT_EXPIRED"));
        assertThat(snapshot()).isEqualTo(before);

        // Bob took it over: the old check-in cannot overwrite him
        checkOut(bob.getUserId(), java.time.OffsetDateTime.now().minusMinutes(10), java.time.OffsetDateTime.now().plusHours(70));
        String afterTakeover = snapshot();
        assertThatThrownBy(() -> promote(REPLACEMENT, alice, submitted)).isInstanceOfSatisfying(
                PromotionRefusedException.class, e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_CHECKED_OUT"));
        assertThat(snapshot()).isEqualTo(afterTakeover);

        // the same user takes a NEW lease after expiry: the old check-in cannot use it
        checkOut(alice.getUserId(), java.time.OffsetDateTime.now().minusMinutes(10), java.time.OffsetDateTime.now().plusHours(70));
        assertThatThrownBy(() -> promote(REPLACEMENT, alice, submitted)).isInstanceOfSatisfying(
                PromotionRefusedException.class, e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_CHECKED_OUT"));
    }

    @Test
    void aForceReleasedCheckOut_leavesNothingToAuthoriseAnything_documentedLimitation() {
        checkOut(alice.getUserId(), java.time.OffsetDateTime.now().minusHours(2), java.time.OffsetDateTime.now().plusHours(70));
        documents.releaseCheckoutAnyHolder(documentId, tenantId);
        assertThat(checkedOutBy()).isNull();

        // no check-out left on the row, so the upload promotes like any other (a conflict would need a base version)
        UUID version = promote(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(30));
        assertThat(currentVersion()).isEqualTo(version);
    }

    @Test
    void signingGeneratedCopies_stillPromote_whoeverHoldsTheCheckOut() {
        User bob = fx.user(tenantId, "Bob");
        checkOut(bob.getUserId(), java.time.OffsetDateTime.now().minusHours(1), java.time.OffsetDateTime.now().plusHours(70));
        UUID version = promote(SIGNED_COPY, alice, java.time.OffsetDateTime.now().minusMinutes(5));
        assertThat(currentVersion()).isEqualTo(version);
    }

    @Test
    void theDoc06RulesStillApply_alongsideTheCheckOutRule() {
        checkOut(alice.getUserId(), java.time.OffsetDateTime.now().minusHours(2), java.time.OffsetDateTime.now().plusHours(70));
        placeLegalHold();
        assertThatThrownBy(() -> promote(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(5)))
                .isInstanceOfSatisfying(PromotionRefusedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DOCUMENT_LEGAL_HOLD"));
    }

    // ------------------------------------------------------------------ DOC-08: version numbers, on real rows

    @Test
    void theFirstVersionAndSequentialPromotions_areNumberedConsecutively_withTheRightLabels() {
        assertThat(versionNumber(currentVersion())).isEqualTo(1);
        assertThat(versionLabel(currentVersion())).isEqualTo("1.0");

        UUID second = fx.newVersion(tenantId, documentId, alice);
        UUID restored = fx.newVersion(tenantId, documentId, alice, ROLLBACK);
        UUID stamped = fx.newVersion(tenantId, documentId, alice, SIGNED_COPY);

        assertThat(versionNumber(second)).isEqualTo(2);
        assertThat(versionNumber(restored)).isEqualTo(3);
        assertThat(versionNumber(stamped)).isEqualTo(4);
        assertThat(versionLabel(second)).isEqualTo("1.1");
        assertThat(versionLabel(restored)).isEqualTo("1.2");
        assertThat(versionLabel(stamped)).isEqualTo("1.3");
        assertThat(currentVersion()).isEqualTo(stamped);
    }

    /**
     * The DOC-08 scenario on a real database: two uploads of one document are prepared before either promotes (so both
     * propose the same number), then promoted in two real transactions - the second one waits for the first one's row
     * lock and must still get its own, next number. The first is paused inside its transaction (after its insert,
     * holding the lock); the database is asked when the second is blocked; nothing relies on time passing.
     */
    @Test
    void twoUploadsPreparedTogether_bothPromote_withConsecutiveNumbers_andNoUniqueViolation() throws Exception {
        java.time.OffsetDateTime submitted = java.time.OffsetDateTime.now().minusMinutes(5);
        Prepared a = prepared(REPLACEMENT, alice, submitted);
        Prepared b = prepared(REPLACEMENT, alice, submitted);
        assertThat(a.plan().versionNumber()).as("both ingestions proposed the same next number").isEqualTo(2);
        assertThat(b.plan().versionNumber()).isEqualTo(2);

        Hook.reached = new CountDownLatch(1);
        Hook.proceed = new CountDownLatch(1);
        CompletableFuture<UUID> promotingA = CompletableFuture.supplyAsync(() -> persistPrepared(a));
        assertThat(Hook.reached.await(30, TimeUnit.SECONDS)).isTrue();   // A has inserted its row and holds the lock

        Hook.reached = null;                                              // only A is paused
        CompletableFuture<UUID> promotingB = CompletableFuture.supplyAsync(() -> persistPrepared(b));
        awaitBlockedSession(promotingB);
        assertThat(promotingB).as("B waits for A's row lock").isNotDone();

        Hook.proceed.countDown();
        UUID versionA = promotingA.get(30, TimeUnit.SECONDS);
        UUID versionB = promotingB.get(30, TimeUnit.SECONDS);             // no DataIntegrityViolation

        assertThat(versionNumber(versionA)).isEqualTo(2);
        assertThat(versionNumber(versionB)).as("not the stale hint").isEqualTo(3);
        assertThat(versionLabel(versionA)).isEqualTo("1.1");
        assertThat(versionLabel(versionB)).isEqualTo("1.2");
        assertThat(versionCount()).isEqualTo(3);
        assertThat(currentVersion()).as("the later commit is current").isEqualTo(versionB);
        assertThat(latestVersion()).isEqualTo(versionB);

        // each row kept the object key it was prepared with - B's key still says v2, its number is 3
        assertThat(storedKey(versionA)).isEqualTo(a.plan().objectKey()).contains("/v2/");
        assertThat(storedKey(versionB)).isEqualTo(b.plan().objectKey()).contains("/v2/");

        // the unique constraint is still there: forcing a duplicate number is rejected by the database
        assertThatThrownBy(() -> fx.jdbc().update("update document_versions set version_number = 2 where version_id = ?",
                versionB)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("uq_document_versions_document_number");
        assertThat(versionNumber(versionB)).isEqualTo(3);

        // both files are reachable by their stored keys (the mismatch with the number is harmless)...
        fx.putVersionContent(versionA, "FILE A".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fx.putVersionContent(versionB, "FILE B".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.oak.dms.security.auth.TenantContext.set(tenantId, alice.getUserId());
        assertThat(read(versionService.download(documentId, versionA))).isEqualTo("FILE A");
        assertThat(read(versionService.download(documentId, versionB))).isEqualTo("FILE B");

        // ...and cleanup works by the stored key, not by any number
        var row = fx.jdbc().queryForMap("select storage_location_id, storage_object_key from document_versions "
                + "where version_id = ?", versionB);
        com.oak.dms.storage.entity.StorageLocation location = storageLocationRepository
                .findById((UUID) row.get("storage_location_id")).orElseThrow();
        var store = storage.resolve(location);
        assertThat(store.exists(location, (String) row.get("storage_object_key"))).isTrue();
        store.delete(location, (String) row.get("storage_object_key"));
        assertThat(store.exists(location, (String) row.get("storage_object_key"))).isFalse();
        assertThat(store.exists(location, storedKey(versionA))).as("A's object is untouched").isTrue();
    }

    @Test
    void aRefusedPromotion_takesNoNumber_andTheNextOneGetsTheFreeOne() {
        Prepared first = prepared(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(5));
        placeLegalHold(); // arrives after the plan was made, before the version is written
        assertThatThrownBy(() -> persistPrepared(first)).isInstanceOf(PromotionRefusedException.class);
        assertThat(versionCount()).isEqualTo(1);

        fx.jdbc().update("update documents set legal_hold = false, legal_hold_at = null, legal_hold_by = null, "
                + "legal_hold_reason = null where document_id = ?", documentId);
        Prepared next = prepared(REPLACEMENT, alice, java.time.OffsetDateTime.now().minusMinutes(5));
        assertThat(versionNumber(persistPrepared(next))).isEqualTo(2);
    }

    // ------------------------------------------------------------------ concurrency (real transactions, no sleeps)

    @Test
    void aPromotion_waitsForALockBeingAppliedElsewhere_thenRefusesOnTheCommittedState() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager());

        // T2: sets the workflow signature lock and holds the document row, uncommitted
        CompletableFuture<Void> applying = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                    + "latest_version_id where document_id = ?", documentId);
            locked.countDown();
            await(commit);
        }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        // T1: a promotion that has already been accepted
        CompletableFuture<UUID> promoting = CompletableFuture.supplyAsync(
                () -> fx.newVersion(tenantId, documentId, alice));
        awaitBlockedSession(promoting);
        assertThat(promoting).as("the promotion must be waiting on the row lock, not reading the old state").isNotDone();

        commit.countDown();
        applying.get(30, TimeUnit.SECONDS);

        assertThatThrownBy(() -> promoting.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PromotionRefusedException.class)
                .cause().satisfies(e -> assertThat(((PromotionRefusedException) e).getCode())
                        .isEqualTo("DOCUMENT_SIGNATURE_LOCKED"));
        assertThat(versionCount()).as("no version was added").isEqualTo(1);
        assertThat(currentVersion()).isEqualTo(latestVersion());
    }

    @Test
    void aLockArrivingMidPromotion_waitsForIt_andTheTwoSerialiseIntoAConsistentState() throws Exception {
        Hook.reached = new CountDownLatch(1);
        Hook.proceed = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager());

        // T1: the promotion, paused inside its transaction after the document row was updated
        CompletableFuture<UUID> promoting = CompletableFuture.supplyAsync(() -> fx.newVersion(tenantId, documentId, alice));
        assertThat(Hook.reached.await(30, TimeUnit.SECONDS)).isTrue();

        // T2: a signature lock for whatever the latest version is - it must wait for T1's row lock
        CompletableFuture<Void> applying = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status ->
                fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                        + "latest_version_id where document_id = ?", documentId)));
        awaitBlockedSession(applying);
        assertThat(applying).as("the lock must wait for the promotion in flight").isNotDone();

        Hook.proceed.countDown();
        UUID promoted = promoting.get(30, TimeUnit.SECONDS);
        applying.get(30, TimeUnit.SECONDS);

        // serialised: the promotion first, then the lock - and the lock names the version that is current
        assertThat(currentVersion()).isEqualTo(promoted);
        assertThat(fx.jdbc().queryForObject("select signature_locked_version_id from documents where document_id = ?",
                UUID.class, documentId)).isEqualTo(promoted);
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void aPromotion_waitsForACheckOutBeingTakenElsewhere_thenRefusesOnTheCommittedState() throws Exception {
        User bob = fx.user(tenantId, "Bob");
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager());

        // T2: Bob checks the document out (the real acquire statement) and holds the row, uncommitted
        CompletableFuture<Integer> acquiring = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            int won = documents.acquireCheckout(documentId, tenantId, bob.getUserId(), java.time.OffsetDateTime.now(),
                    java.time.OffsetDateTime.now().plusHours(72));
            taken.countDown();
            await(commit);
            return won;
        }));
        assertThat(taken.await(30, TimeUnit.SECONDS)).isTrue();

        // T1: an upload accepted before that check-out, being promoted now
        java.time.OffsetDateTime submitted = java.time.OffsetDateTime.now().minusMinutes(5);
        CompletableFuture<UUID> promoting = CompletableFuture.supplyAsync(() -> promote(REPLACEMENT, alice, submitted));
        awaitBlockedSession(promoting);
        assertThat(promoting).isNotDone();

        commit.countDown();
        assertThat(acquiring.get(30, TimeUnit.SECONDS)).isEqualTo(1);

        assertThatThrownBy(() -> promoting.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PromotionRefusedException.class)
                .cause().satisfies(e -> assertThat(((PromotionRefusedException) e).getCode()).isEqualTo("DOCUMENT_CHECKED_OUT"));
        assertThat(versionCount()).isEqualTo(1);
        assertThat(checkedOutBy()).isEqualTo(bob.getUserId());
    }

    @Test
    void aCheckOutArrivingMidPromotion_waitsForIt_andTheTwoSerialise() throws Exception {
        User bob = fx.user(tenantId, "Bob");
        Hook.reached = new CountDownLatch(1);
        Hook.proceed = new CountDownLatch(1);
        java.time.OffsetDateTime submitted = java.time.OffsetDateTime.now().minusMinutes(5);

        // T1: the promotion, paused inside its transaction after the document row was updated
        CompletableFuture<UUID> promoting = CompletableFuture.supplyAsync(() -> promote(REPLACEMENT, alice, submitted));
        assertThat(Hook.reached.await(30, TimeUnit.SECONDS)).isTrue();

        // T2: Bob's check-out must wait for T1's row lock
        CompletableFuture<Integer> acquiring = CompletableFuture.supplyAsync(() -> documents.acquireCheckout(documentId,
                tenantId, bob.getUserId(), java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now().plusHours(72)));
        awaitBlockedSession(acquiring);
        assertThat(acquiring).isNotDone();

        Hook.proceed.countDown();
        UUID promoted = promoting.get(30, TimeUnit.SECONDS);
        assertThat(acquiring.get(30, TimeUnit.SECONDS)).as("Bob then got the check-out").isEqualTo(1);

        // serialised: the version landed first, then Bob took the document - no half-state, no lost row
        assertThat(currentVersion()).isEqualTo(promoted);
        assertThat(checkedOutBy()).isEqualTo(bob.getUserId());
        assertThat(versionCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.transaction.PlatformTransactionManager transactionManager() {
        return context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
    }

    private void assertRefused(String createdReason, String code) {
        assertThatThrownBy(() -> fx.newVersion(tenantId, documentId, alice, createdReason))
                .as(createdReason).isInstanceOfSatisfying(PromotionRefusedException.class,
                        e -> assertThat(e.getCode()).isEqualTo(code));
    }

    /**
     * What the ingestion gate hands the promoter once a file is clean: a scan result of this uploader, accepted at
     * {@code startedAt} - prepare, then persist, in one transaction. (WorkflowFixtures.newVersion does the same but
     * cannot set the submission time, which the check-out rule compares with the lease.)
     */
    private UUID promote(String createdReason, User uploader, java.time.OffsetDateTime startedAt) {
        return new TransactionTemplate(transactionManager()).execute(status -> {
            ScanEngine engine = scanEngineRepository.findPlatformDefault(ScanEngine.Status.active).orElseThrow();
            StorageLocation staging = storageLocationRepository.findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(
                    tenantId, StoragePurpose.staging, StorageLocation.Status.active).orElseThrow();
            byte[] hash = new byte[32];
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(hash);
            ScanResult scan = ScanResult.builder().tenantId(tenantId).scanEngineId(engine.getScanEngineId())
                    .stagingLocationId(staging.getStorageLocationId()).originalFilename("contract.pdf")
                    .fileHash(java.util.HexFormat.of().formatHex(hash)).sizeBytes(1024L).mimeType("application/pdf")
                    .status(ScanResultStatus.clean).engineName("promotion-it").scanMode(ScanMode.pre_store_stream)
                    .completedAt(java.time.OffsetDateTime.now()).startedAt(startedAt).uploadedBy(uploader.getUserId())
                    .ingestionKind(IngestionKind.new_version).targetDocumentId(documentId).createdReason(createdReason)
                    .createdBy(uploader.getUserId()).build();
            DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
            scan.setDocumentVersionId(plan.versionId());
            scanResultRepository.save(scan);
            promoter.persist(plan, scan);
            return plan.versionId();
        });
    }

    private void checkOut(UUID holder, java.time.OffsetDateTime taken, java.time.OffsetDateTime expires) {
        fx.jdbc().update("update documents set checked_out_by = ?, checked_out_at = ?, checkout_expires_at = ? "
                + "where document_id = ?", holder, taken, expires, documentId);
    }

    private UUID checkedOutBy() {
        return fx.jdbc().queryForObject("select checked_out_by from documents where document_id = ?", UUID.class, documentId);
    }

    private record Prepared(ScanResult scan, DocumentPromoter.PromotionPlan plan) {
    }

    /** What the gate holds once a file is clean, after prepare and before persist - nothing is written yet. */
    private Prepared prepared(String createdReason, User uploader, java.time.OffsetDateTime startedAt) {
        ScanEngine engine = scanEngineRepository.findPlatformDefault(ScanEngine.Status.active).orElseThrow();
        StorageLocation staging = storageLocationRepository.findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(
                tenantId, StoragePurpose.staging, StorageLocation.Status.active).orElseThrow();
        byte[] hash = new byte[32];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(hash);
        ScanResult scan = ScanResult.builder().tenantId(tenantId).scanEngineId(engine.getScanEngineId())
                .stagingLocationId(staging.getStorageLocationId()).originalFilename("contract.pdf")
                .fileHash(java.util.HexFormat.of().formatHex(hash)).sizeBytes(1024L).mimeType("application/pdf")
                .status(ScanResultStatus.clean).engineName("promotion-it").scanMode(ScanMode.pre_store_stream)
                .completedAt(java.time.OffsetDateTime.now()).startedAt(startedAt).uploadedBy(uploader.getUserId())
                .ingestionKind(IngestionKind.new_version).targetDocumentId(documentId).createdReason(createdReason)
                .createdBy(uploader.getUserId()).build();
        DocumentPromoter.PromotionPlan plan = promoter.prepare(scan);
        scan.setDocumentVersionId(plan.versionId());
        return new Prepared(scan, plan);
    }

    /** The write half of a promotion: the scan row and the version in one transaction, as the processor does. */
    private UUID persistPrepared(Prepared prepared) {
        return new TransactionTemplate(transactionManager()).execute(status -> {
            scanResultRepository.save(prepared.scan());
            promoter.persist(prepared.plan(), prepared.scan());
            return prepared.plan().versionId();
        });
    }

    private int versionNumber(UUID versionId) {
        return fx.jdbc().queryForObject("select version_number from document_versions where version_id = ?",
                Integer.class, versionId);
    }

    private String versionLabel(UUID versionId) {
        return fx.jdbc().queryForObject("select version_label from document_versions where version_id = ?",
                String.class, versionId);
    }

    private String storedKey(UUID versionId) {
        return fx.jdbc().queryForObject("select storage_object_key from document_versions where version_id = ?",
                String.class, versionId);
    }

    private static String read(com.oak.dms.document.dto.DownloadedDocument downloaded) {
        try (java.io.InputStream in = downloaded.content()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void lockToCurrentVersion() {
        fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                + "current_version_id where document_id = ?", documentId);
    }

    private void placeLegalHold() {
        fx.jdbc().update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, "
                + "legal_hold_reason = 'test' where document_id = ?", alice.getUserId(), documentId);
    }

    private void markCurrentAsSigned() {
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion());
    }

    private UUID currentVersion() {
        return fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, documentId);
    }

    private UUID latestVersion() {
        return fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?", UUID.class, documentId);
    }

    private long versionCount() {
        return fx.count("select count(*) from document_versions where document_id = ?", documentId);
    }

    /** Everything a refused promotion must leave alone, as one comparable string. */
    private String snapshot() {
        return fx.jdbc().queryForObject("""
                select (select count(*) from document_versions where document_id = ?) || '|'
                    || (select count(*) from scan_results where tenant_id = ?) || '|'
                    || (select count(*) from document_signatures where document_id = ?) || '|'
                    || d.current_version_id || '|' || d.latest_version_id || '|' || d.legal_hold || '|'
                    || coalesce(d.signature_locked_at::text, '-') || '|' || coalesce(d.signature_locked_version_id::text, '-')
                    || '|' || d.is_deleted || '|'
                    || (select string_agg(version_id || ':' || created_reason, ',' order by version_id)
                          from document_versions where document_id = ?)
                  from documents d where d.document_id = ?
                """, String.class, documentId, tenantId, documentId, documentId, documentId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits until the database reports a session blocked on a lock - the other transaction's wait is the thing being
     * tested, so the database is asked rather than time being allowed to pass. Gives up (failing the test) only if the
     * blocked work finished first, or after a generous overall limit.
     */
    private void awaitBlockedSession(CompletableFuture<?> blockedWork) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Integer waiting = fx.jdbc().queryForObject("""
                    select count(*) from pg_stat_activity
                     where datname = current_database() and pid <> pg_backend_pid() and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (blockedWork.isDone()) {
                throw new AssertionError("the work finished without ever waiting on a lock");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("no session became blocked on a lock");
    }
}
