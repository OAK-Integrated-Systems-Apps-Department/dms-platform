package com.oak.dms.watermark.entity;

import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Placement and page scope are stored lowercase and hyphenated
 * ({@code header-footer}, {@code first-page-only}) to match the CHECK
 * constraints in V68 and the values the API accepts, so - unlike every other
 * enum column in this codebase - the constant name isn't the stored value
 * and {@code @Enumerated(EnumType.STRING)} would write {@code HEADER_FOOTER}.
 * These converters are {@code autoApply}, so the entities just declare the
 * enum type and nothing has to remember.
 */
public final class WatermarkEnumConverters {

    private WatermarkEnumConverters() {
    }

    @Converter(autoApply = true)
    public static class PlacementConverter implements AttributeConverter<WatermarkPlacement, String> {

        @Override
        public String convertToDatabaseColumn(WatermarkPlacement attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public WatermarkPlacement convertToEntityAttribute(String dbData) {
            return dbData == null ? null : WatermarkPlacement.fromValue(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class PageScopeConverter implements AttributeConverter<WatermarkPageScope, String> {

        @Override
        public String convertToDatabaseColumn(WatermarkPageScope attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public WatermarkPageScope convertToEntityAttribute(String dbData) {
            return dbData == null ? null : WatermarkPageScope.fromValue(dbData);
        }
    }
}
