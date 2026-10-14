import { NotFoundView } from "@/components/errors/not-found-view"

/**
 * The 404 for any address that doesn't exist (and for `notFound()` anywhere
 * in the app). Unsigned visitors never get here - proxy.ts sends them to
 * /login first - except on the public paths.
 */
export default function NotFound() {
  return <NotFoundView />
}
