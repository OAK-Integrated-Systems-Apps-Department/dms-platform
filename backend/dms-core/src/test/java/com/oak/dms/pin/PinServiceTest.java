package com.oak.dms.pin;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rules around a pin: who may pin what, the limit, and what happens when a document goes away. */
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
        ReflectionTestUtils.setField(service, "defaultMax", 3);
        TenantContext.set(tenantId, userId);
        // No pin_settings row: the default applies.
        when(jdbc.queryForList(contains("from pin_settings"), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DocumentListItem item(UUID id) {
        return new DocumentListItem(id, "Contract", UUID.randomUUID(), "Contract", null, "c.pdf", 10L,
                "application/pdf", 1, UUID.randomUUID(), OffsetDateTime.now(), false, "view");
    }

    private void visible(boolean yes) {
        when(documents.visibleItems(any())).thenReturn(yes ? List.of(item(documentId)) : List.of());
    }

    private void pinsHeld(int alreadyThisOne, int total) {
        when(jdbc.queryForObject(contains("and document_id = :document"), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(alreadyThisOne);
        when(jdbc.queryForObject(contains("select count(*) from document_pins where tenant_id"),
                any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(total);
    }

    @Test
    void aDocumentTheCallerCannotSeeIsNotFound_neverPinned() {
        visible(false);

        assertThatThrownBy(() -> service.pin(documentId)).isInstanceOf(ResourceNotFoundException.class);
        verify(jdbc, never()).update(contains("insert into document_pins"), any(MapSqlParameterSource.class));
    }

    @Test
    void pinsAVisibleDocument_underTheLimit() {
        visible(true);
        pinsHeld(0, 2);

        service.pin(documentId);

        verify(jdbc).update(contains("insert into document_pins"), any(MapSqlParameterSource.class));
    }

    @Test
    void pinningAgainIsNotAnErrorAndAddsNothing() {
        visible(true);
        pinsHeld(1, 3);

        service.pin(documentId);

        verify(jdbc, never()).update(contains("insert into document_pins"), any(MapSqlParameterSource.class));
    }

    @Test
    void atTheLimit_isRefusedWithAMessageSayingWhy() {
        visible(true);
        pinsHeld(0, 3);

        assertThatThrownBy(() -> service.pin(documentId))
                .isInstanceOfSatisfying(FolderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PIN_LIMIT_REACHED");
                    assertThat(e.getMessage()).contains("3 documents");
                });
        verify(jdbc, never()).update(contains("insert into document_pins"), any(MapSqlParameterSource.class));
    }

    @Test
    void anOrganisationLimitOverridesTheDefault() {
        visible(true);
        pinsHeld(0, 3);
        when(jdbc.queryForList(contains("from pin_settings"), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of(5));

        service.pin(documentId);

        verify(jdbc).update(contains("insert into document_pins"), any(MapSqlParameterSource.class));
    }

    @Test
    void theLimitMustBeSensible() {
        assertThatThrownBy(() -> service.setLimit(0)).isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> service.setLimit(201)).isInstanceOf(FolderException.class);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void pinsWhoseDocumentIsGone_areDroppedWhenTheListIsRead() {
        UUID gone = UUID.randomUUID();
        // Two pins: one still visible, one not (deleted, or access revoked).
        org.mockito.Mockito.doAnswer(call -> {
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            for (UUID id : List.of(documentId, gone)) {
                when(rs.getObject("document_id", UUID.class)).thenReturn(id);
                when(rs.getObject("pinned_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
                ((RowCallbackHandler) call.getArgument(2)).processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("from document_pins where tenant_id"), any(MapSqlParameterSource.class),
                any(RowCallbackHandler.class));
        when(documents.visibleItems(any())).thenReturn(List.of(item(documentId)));

        PinService.Pins pins = service.list();

        assertThat(pins.pins()).extracting(p -> p.document().documentId()).containsExactly(documentId);
        assertThat(pins.limit()).isEqualTo(3);
        verify(jdbc).update(contains("delete from document_pins"), any(MapSqlParameterSource.class));
    }
}
