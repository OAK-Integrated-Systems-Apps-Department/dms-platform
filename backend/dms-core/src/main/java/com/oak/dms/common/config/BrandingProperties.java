package com.oak.dms.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the product's customer-facing display name -
 * backed by {@code app.branding.product-name} in application.yml
 * (overridable via the {@code PRODUCT_NAME} env var). Everywhere in this
 * codebase that used to hardcode the brand name as a literal string (e.g.
 * {@code PasswordResetService}'s email subjects/bodies, {@code
 * OpenApiConfig}'s title) should inject this instead, so a rebrand is ever
 * only a one-line config change.
 * <p>
 * Not usable inside an annotation attribute (e.g. Springdoc's
 * {@code @Operation(summary = ...)}) - those require compile-time
 * constants, so a handful of Swagger doc strings still can't reference
 * this and were reworded instead to drop the brand name entirely.
 */
@Component
public class BrandingProperties {

    private final String productName;

    public BrandingProperties(@Value("${app.branding.product-name}") String productName) {
        this.productName = productName;
    }

    public String getProductName() {
        return productName;
    }
}
