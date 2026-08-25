/**
 * Base URL of your Java backend (dms-core). Every page in app/ that used
 * to call a local /api/... route now calls `${API_BASE_URL}/api/v1/...`
 * directly instead — there is no Next.js server layer between the
 * browser and dms-core anymore.
 *
 * Must be NEXT_PUBLIC_-prefixed: these fetches run in the browser
 * ("use client" pages), not on a Next.js server, so only env vars
 * exposed at build time are visible here.
 */
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"
