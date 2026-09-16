/** Shared types for the Metadata Fields feature (list, create/edit form, option/validation editors). */

export const DATA_TYPES = [
  "text", "long_text", "number", "decimal", "date", "datetime",
  "boolean", "dropdown", "multi_select", "user_ref", "lookup",
] as const
export type DataType = (typeof DATA_TYPES)[number]

export const DATA_TYPE_LABELS: Record<DataType, string> = {
  text: "Text",
  long_text: "Long Text",
  number: "Number",
  decimal: "Decimal",
  date: "Date",
  datetime: "Date & Time",
  boolean: "Boolean",
  dropdown: "Dropdown",
  multi_select: "Multi-Select",
  user_ref: "User Reference",
  lookup: "Lookup",
}

/** data_type values that need the inline option editor. */
export const OPTION_BACKED_DATA_TYPES: DataType[] = ["dropdown", "multi_select"]

export type FieldStatus = "active" | "inactive" | "deprecated"
