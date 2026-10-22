"use client"

import type { RelationshipTypeDto } from "@/lib/relationships/types"

interface RelationshipTypeSelectProps {
  types: RelationshipTypeDto[]
  value: string
  onChange: (typeId: string) => void
  id?: string
}

/** The enabled relationship types as a dropdown; each option shows how it reads both ways. */
export function RelationshipTypeSelect({ types, value, onChange, id }: RelationshipTypeSelectProps) {
  return (
    <select
      id={id}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="h-10 w-full rounded-btn border border-input bg-transparent px-2.5 text-[13px]"
      aria-label="Relationship type"
    >
      <option value="" disabled>
        Choose a type…
      </option>
      {types.map((t) => (
        <option key={t.relationshipTypeId} value={t.relationshipTypeId}>
          {t.directionality === "symmetric" ? t.label : `${t.label} / ${t.inverseLabel}`}
        </option>
      ))}
    </select>
  )
}
