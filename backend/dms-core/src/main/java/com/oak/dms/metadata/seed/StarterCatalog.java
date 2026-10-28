package com.oak.dms.metadata.seed;

import java.util.List;

/**
 * The starter metadata catalogue every tenant begins with: 24 fields, 8
 * dropdown options, 4 document types and their schema-version-1 field
 * mappings. {@link TenantStarterCatalogService} installs it for new tenants.
 * <p>
 * Values are V30's, with the display orders V31 re-stamped them to.
 * V37 backfills the same catalogue into tenants that existed before this
 * class did, from its own frozen copy; V37MigrationIT asserts the two match,
 * so a change here needs a new migration if existing tenants should get it.
 * <p>
 * classification_level was removed by V102, which also strips it (and its
 * options and mappings) from tenants that already had V37's/the installer's
 * copy of it, leaving any tenant's own customization of the field alone.
 */
public final class StarterCatalog {

    public record FieldDef(String key, String label, String helpText, String dataType, boolean systemField) {
    }

    public record OptionDef(String fieldKey, String value, String label, int displayOrder) {
    }

    public record TypeDef(String code, String name, String description, boolean requiresWatermark,
                          boolean coeditingEnabled) {
    }

    public record MappingDef(String typeCode, String fieldKey, boolean mandatory, int displayOrder,
                             String fieldGroup) {
    }

    public static final List<FieldDef> FIELDS = List.of(
            new FieldDef("department", "Department", "Owning department or business unit.", "lookup", false),
            new FieldDef("tags", "Tags", "Free-form labels for grouping and discovery.", "multi_select", false),
            new FieldDef("retention_class", "Retention Class", "Retention schedule this document falls under.", "dropdown", true),
            new FieldDef("description", "Description", "Free-text summary of the document.", "long_text", false),
            new FieldDef("author", "Author", "Person or team who authored the document.", "text", false),
            new FieldDef("keywords", "Keywords", "Search keywords beyond the free-text tags.", "multi_select", false),
            new FieldDef("language", "Language", "Primary language of the document content.", "dropdown", false),
            new FieldDef("reference_number", "Reference Number", "External or internal reference identifier.", "text", false),
            new FieldDef("effective_date", "Effective Date", "Date the document takes effect.", "date", false),
            new FieldDef("expiry_date", "Expiry Date", "Date the document expires.", "date", false),
            new FieldDef("review_due_date", "Review Due Date", "Date the document is next due for review.", "date", false),
            new FieldDef("invoice_number", "Invoice Number", "Vendor-issued invoice number.", "text", false),
            new FieldDef("invoice_date", "Invoice Date", "Date the invoice was issued.", "date", false),
            new FieldDef("amount", "Amount", "Monetary amount on the document.", "decimal", false),
            new FieldDef("currency", "Currency", "Currency the amount is denominated in.", "dropdown", false),
            new FieldDef("vendor_supplier_name", "Vendor / Supplier Name", "Name of the vendor or supplier.", "text", false),
            new FieldDef("purchase_order_number", "Purchase Order Number", "Related purchase order number.", "text", false),
            new FieldDef("contract_party", "Contract Party", "Counterparty named on the contract.", "text", false),
            new FieldDef("contract_value", "Contract Value", "Total monetary value of the contract.", "decimal", false),
            new FieldDef("contract_start_date", "Contract Start Date", "Date the contract starts.", "date", false),
            new FieldDef("contract_end_date", "Contract End Date", "Date the contract ends.", "date", false),
            new FieldDef("employee_id", "Employee ID", "Related employee identifier.", "text", false),
            new FieldDef("project_code", "Project Code", "Project this document belongs to.", "dropdown", false),
            new FieldDef("cost_centre", "Cost Centre", "Cost centre to charge.", "dropdown", false));

    /**
     * language is deliberately left without options (MDTM alternate flow 1:
     * a dropdown with no active options blocks validation until an admin
     * adds some).
     */
    public static final List<OptionDef> OPTIONS = List.of(
            new OptionDef("currency", "USD", "US Dollar", 10),
            new OptionDef("currency", "EUR", "Euro", 20),
            new OptionDef("currency", "GBP", "British Pound", 30),
            new OptionDef("currency", "LKR", "Sri Lankan Rupee", 40),
            new OptionDef("retention_class", "short_term", "Short-Term (1-3 years)", 10),
            new OptionDef("retention_class", "medium_term", "Medium-Term (4-7 years)", 20),
            new OptionDef("retention_class", "long_term", "Long-Term (8-15 years)", 30),
            new OptionDef("retention_class", "permanent", "Permanent", 40));

    /** In display order; each lands after the tenant's existing types. */
    public static final List<TypeDef> TYPES = List.of(
            new TypeDef("CONTRACT", "Contract", "Legally binding agreements with a counterparty.", true, false),
            new TypeDef("INVOICE", "Invoice", "Vendor or customer billing documents.", false, false),
            new TypeDef("POLICY", "Policy", "Internal governance and policy documents.", true, false),
            new TypeDef("GENERAL", "General", "Catch-all type for documents with no closer fit.", false, true));

    public static final List<MappingDef> MAPPINGS = List.of(
            new MappingDef("CONTRACT", "contract_party", true, 10, "Contract Details"),
            new MappingDef("CONTRACT", "contract_value", true, 20, "Contract Details"),
            new MappingDef("CONTRACT", "contract_start_date", true, 30, "Contract Details"),
            new MappingDef("CONTRACT", "contract_end_date", true, 40, "Contract Details"),
            new MappingDef("CONTRACT", "effective_date", false, 50, "Dates"),
            new MappingDef("CONTRACT", "expiry_date", false, 60, "Dates"),
            new MappingDef("CONTRACT", "review_due_date", false, 70, "Dates"),
            new MappingDef("CONTRACT", "retention_class", true, 90, "Governance"),
            new MappingDef("CONTRACT", "department", false, 100, "Governance"),
            new MappingDef("CONTRACT", "tags", false, 110, "General"),
            new MappingDef("CONTRACT", "description", false, 120, "General"),
            new MappingDef("CONTRACT", "reference_number", false, 130, "General"),
            new MappingDef("INVOICE", "invoice_number", true, 10, "Invoice Details"),
            new MappingDef("INVOICE", "invoice_date", true, 20, "Invoice Details"),
            new MappingDef("INVOICE", "amount", true, 30, "Invoice Details"),
            new MappingDef("INVOICE", "currency", true, 40, "Invoice Details"),
            new MappingDef("INVOICE", "vendor_supplier_name", true, 50, "Invoice Details"),
            new MappingDef("INVOICE", "purchase_order_number", false, 60, "Invoice Details"),
            new MappingDef("INVOICE", "department", false, 80, "Governance"),
            new MappingDef("INVOICE", "cost_centre", false, 90, "Governance"),
            new MappingDef("INVOICE", "tags", false, 100, "General"),
            new MappingDef("INVOICE", "description", false, 110, "General"),
            new MappingDef("POLICY", "department", true, 20, "Governance"),
            new MappingDef("POLICY", "retention_class", true, 30, "Governance"),
            new MappingDef("POLICY", "effective_date", true, 40, "Dates"),
            new MappingDef("POLICY", "expiry_date", false, 50, "Dates"),
            new MappingDef("POLICY", "review_due_date", true, 60, "Dates"),
            new MappingDef("POLICY", "description", false, 70, "General"),
            new MappingDef("POLICY", "author", false, 80, "General"),
            new MappingDef("POLICY", "keywords", false, 90, "General"),
            new MappingDef("POLICY", "language", false, 100, "General"),
            new MappingDef("POLICY", "tags", false, 110, "General"),
            new MappingDef("POLICY", "reference_number", false, 120, "General"),
            new MappingDef("GENERAL", "department", false, 20, "Governance"),
            new MappingDef("GENERAL", "retention_class", false, 30, "Governance"),
            new MappingDef("GENERAL", "description", false, 40, "General"),
            new MappingDef("GENERAL", "tags", false, 50, "General"),
            new MappingDef("GENERAL", "keywords", false, 60, "General"),
            new MappingDef("GENERAL", "author", false, 70, "General"),
            new MappingDef("GENERAL", "language", false, 80, "General"),
            new MappingDef("GENERAL", "reference_number", false, 90, "General"),
            new MappingDef("GENERAL", "effective_date", false, 100, "Dates"),
            new MappingDef("GENERAL", "expiry_date", false, 110, "Dates"));

    private StarterCatalog() {
    }
}
