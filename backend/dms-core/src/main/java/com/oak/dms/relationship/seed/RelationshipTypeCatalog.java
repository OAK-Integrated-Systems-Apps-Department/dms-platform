package com.oak.dms.relationship.seed;

import java.util.List;

/**
 * The ten system relationship types every tenant starts with. V107 backfills existing tenants
 * with the same rows; {@code RelationshipTypeSeedParityTest} keeps the two lists identical.
 */
public final class RelationshipTypeCatalog {

    public record Seed(String code, String category, String label, String inverseLabel, String directionality,
                       boolean preventsCycles, int displayOrder) {
    }

    public static final List<Seed> SYSTEM_TYPES = List.of(
            new Seed("PARENT_CHILD", "structure", "Parent of", "Child of", "directional", true, 10),
            new Seed("MAIN_ATTACHMENT", "structure", "Has attachment", "Attachment of", "directional", false, 20),
            new Seed("CONTRACT_AMENDMENT", "contract", "Has amendment", "Amends", "directional", false, 30),
            new Seed("SUPERSEDES", "versioning", "Supersedes", "Superseded by", "directional", true, 40),
            new Seed("REPLACES", "versioning", "Replaces", "Replaced by", "directional", true, 50),
            new Seed("INVOICE_SUPPORTING", "evidence", "Has supporting document", "Supports", "directional", false, 60),
            new Seed("REPORT_EVIDENCE", "evidence", "Has evidence", "Evidence for", "directional", false, 70),
            new Seed("ORIGINAL_TRANSLATION", "language", "Has translation", "Translation of", "directional", false, 80),
            new Seed("DUPLICATE_OF", "duplicate", "Duplicate of", "Has duplicate", "directional", false, 90),
            new Seed("RELATED", "association", "Related document", "Related document", "symmetric", false, 100));

    public static final String PARENT_CHILD = "PARENT_CHILD";
    public static final String SUPERSEDES = "SUPERSEDES";
    public static final String REPLACES = "REPLACES";
    public static final String DUPLICATE_OF = "DUPLICATE_OF";
    public static final String RELATED = "RELATED";

    private RelationshipTypeCatalog() {
    }
}
