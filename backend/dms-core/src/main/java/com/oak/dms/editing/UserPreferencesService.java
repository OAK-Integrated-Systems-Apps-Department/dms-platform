package com.oak.dms.editing;

import com.oak.dms.checkout.CheckoutException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The signed-in user's own interface preferences. Self-service and cosmetic: a preference changes how a page is laid
 * out, never what the user may do, so it is always the caller's own row and needs no permission.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    public static final String FULLPAGE = "fullpage";
    public static final String INLINE = "inline";

    public record View(String editorLayout) {
    }

    private final UserPreferencesRepository repository;

    @Transactional(readOnly = true)
    public View mine() {
        return repository.findById(TenantContext.getUserId())
                .map(p -> new View(p.getEditorLayout()))
                .orElse(new View(INLINE));
    }

    @Transactional
    public View save(String editorLayout) {
        if (!FULLPAGE.equals(editorLayout) && !INLINE.equals(editorLayout)) {
            throw CheckoutException.badRequest("EDITOR_LAYOUT_INVALID",
                    "Choose \"fullpage\" or \"inline\" for where the editor opens.");
        }
        UUID me = TenantContext.getUserId();
        UserPreferences row = repository.findById(me).orElseGet(() -> UserPreferences.builder()
                .userId(me).tenantId(TenantContext.getTenantId()).editorLayout(INLINE).build());
        row.setEditorLayout(editorLayout);
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);
        return new View(editorLayout);
    }
}
