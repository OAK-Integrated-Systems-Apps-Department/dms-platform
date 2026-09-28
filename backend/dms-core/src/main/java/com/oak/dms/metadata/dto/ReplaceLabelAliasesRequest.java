package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code PUT /document-types/{documentTypeId}/fields/{fieldId}/label-aliases}:
 * the complete, ordered list of OCR label hints for that field on that
 * document type only (list order = match priority). Replaces the existing
 * list wholesale; {@code []} clears it. Entries are normalized/validated in
 * the service layer ({@code LabelAliasNormalizer}), not here - the rules
 * depend on the normalized form, which a Bean Validation annotation can't
 * see - but a missing/null list is rejected up front, since "leave
 * unchanged" has no meaning on an endpoint whose only job is to set it.
 */
public record ReplaceLabelAliasesRequest(

        @NotNull
        List<String> labelAliases
) {
}
