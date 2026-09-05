import Link from "next/link"
import { Fragment } from "react"

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"

export interface AdminBreadcrumbSegment {
  /** Text shown for this crumb. */
  label: string
  /**
   * Route for this crumb. Omit for the current page (rendered as
   * `BreadcrumbPage`) or for a non-navigable ancestor like the
   * "Governance" section label.
   */
  href?: string
}

/**
 * Breadcrumb trail for the User Management / Role Management pages under
 * `app/admin/users` and `app/admin/roles`, e.g.
 *
 *   <AdminBreadcrumb
 *     segments={[
 *       { label: "Governance" },
 *       { label: "Users", href: "/admin/users" },
 *       { label: user?.name ?? "View User" },
 *     ]}
 *   />
 *
 * The last segment is always rendered as the current page (no link,
 * `aria-current="page"`) regardless of whether it has an `href`.
 */
export function AdminBreadcrumb({
  segments,
  className,
}: {
  segments: AdminBreadcrumbSegment[]
  className?: string
}) {
  return (
    <Breadcrumb className={className}>
      <BreadcrumbList>
        {segments.map((segment, index) => {
          const isLast = index === segments.length - 1
          return (
            <Fragment key={`${segment.label}-${index}`}>
              <BreadcrumbItem>
                {isLast || !segment.href ? (
                  <BreadcrumbPage>{segment.label}</BreadcrumbPage>
                ) : (
                  <BreadcrumbLink asChild>
                    <Link href={segment.href}>{segment.label}</Link>
                  </BreadcrumbLink>
                )}
              </BreadcrumbItem>
              {!isLast && <BreadcrumbSeparator />}
            </Fragment>
          )
        })}
      </BreadcrumbList>
    </Breadcrumb>
  )
}
