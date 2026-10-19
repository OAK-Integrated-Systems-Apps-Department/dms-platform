import { z } from "zod"

import { OTP_LENGTH } from "@/components/auth/otp-input"

/**
 * The signing ceremony form - comment (optional, same 2000-char cap as a
 * plain approval), password and OTP (step-up re-authentication, never
 * persisted past the request - see `SignAndApproveDialog`'s own doc
 * comment), and an explicit consent checkbox. `meaning` is always
 * "approved" for this dialog (sign-and-approve IS an approval) - not a
 * field the signer picks.
 */
export const signAndApproveSchema = z.object({
  comment: z.string().trim().max(2000, "Keep it under 2000 characters"),
  password: z.string().min(1, "Enter your password"),
  totp: z.string().refine((value) => value.length === 0 || value.length === OTP_LENGTH, "Enter all 6 digits"),
  consent: z.boolean().refine((value) => value === true, "Confirm you intend to sign this document"),
})
export type SignAndApproveFormValues = z.infer<typeof signAndApproveSchema>
