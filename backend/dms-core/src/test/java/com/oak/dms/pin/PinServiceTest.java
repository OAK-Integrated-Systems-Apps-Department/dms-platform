package com.oak.dms.pin;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.pin.PinService.AudienceMode;
import com.oak.dms.pin.PinService.AudienceRequest;
import com.oak.dms.pin.PinService.PinnedDocument;
import com.oak.dms.pin.PinService.Row;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules around a pin that need no database: what a request must carry, and how one person's view of a
 * document pinned by several people is chosen. Who sees what, the limit and the unpin rule are exercised
 * against a real database in {@link PinAudienceIT}.
 */
class PinServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private NamedParameterJdbcTemplate jdbc;
    private DocumentService documents;
    private PinService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        documents = mock(DocumentService.class);
        service = new PinService(jdbc, documents, mock(AuditService.class));
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DocumentListItem item(UUID id) {
        return new DocumentListItem(id, "Contract", UUID.randomUUID(), "Contract", null, "c.pdf", 10L,
                "application/pdf", 1, UUID.randomUUID(), OffsetDateTime.now(), false, "view");
    }

    private static Row row(UUID document, UUID pinnedBy, String name, AudienceMode mode, OffsetDateTime at, int people) {
        return new Row(UUID.randomUUID(), document, pinnedBy, name, mode, at, people);
    }

    @Test
    void aPinWithNoAudienceIsRefused_andTouchesNothing() {
        assertThatThrownBy(() -> service.pin(documentId, null)).isInstanceOfSatisfying(FolderException.class,
                e -> assertThat(e.getCode()).isEqualTo("PIN_AUDIENCE_REQUIRED"));
        assertThatThrownBy(() -> service.pin(documentId, new AudienceRequest(null, List.of())))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PIN_AUDIENCE_REQUIRED"));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void aDocumentTheCallerCannotSeeIsNotFound_neverPinned() {
        when(documents.visibleItems(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.pin(documentId, new AudienceRequest(AudienceMode.ALL, List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void specificUsersNeedsAtLeastOnePerson() {
        when(documents.visibleItems(any())).thenReturn(List.of(item(documentId)));

        assertThatThrownBy(() -> service.pin(documentId, new AudienceRequest(AudienceMode.USERS, List.of())))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PIN_AUDIENCE_EMPTY"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void theLimitMustBeSensible() {
        assertThatThrownBy(() -> service.setLimit(0)).isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> service.setLimit(201)).isInstanceOf(FolderException.class);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void whenSeveralPeoplePinnedIt_theCallersOwnPinWins_otherwiseTheEarliestPinner() {
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(
                row(documentId, other, "Other", AudienceMode.ALL, now.minusDays(1), 0),
                row(documentId, userId, "Me", AudienceMode.USERS, now.minusDays(2), 3),
                row(documentId, third, "Third", AudienceMode.ALL, now.minusDays(3), 0));

        PinnedDocument mine = PinService.pinnedDocuments(rows, Map.of(documentId, item(documentId)), Set.of(), userId)
                .get(0);
        assertThat(mine.mine()).isTrue();
        assertThat(mine.audience()).isEqualTo(new PinService.AudienceSummary(AudienceMode.USERS, 3));

        PinnedDocument theirs = PinService.pinnedDocuments(List.of(rows.get(0), rows.get(2)),
                Map.of(documentId, item(documentId)), Set.of(), userId).get(0);
        assertThat(theirs.mine()).isFalse();
        assertThat(theirs.pinnedByName()).as("the earliest pinner").isEqualTo("Third");
        assertThat(theirs.audience()).as("someone else's audience isn't passed on").isNull();
    }

    @Test
    void documentsTheCallerCannotSeeAreLeftOut_andTheRestAreNewestFirst() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        UUID someone = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(
                row(hidden, someone, "S", AudienceMode.ALL, now, 0),
                row(newer, someone, "S", AudienceMode.ALL, now.minusHours(1), 0),
                row(older, someone, "S", AudienceMode.ALL, now.minusHours(2), 0));

        List<PinnedDocument> pins = PinService.pinnedDocuments(rows,
                Map.of(older, item(older), newer, item(newer)), Set.of(older), userId);

        assertThat(pins).extracting(p -> p.document().documentId()).containsExactly(newer, older);
        assertThat(pins).extracting(PinnedDocument::archived).containsExactly(false, true);
    }
}
