import { z } from "zod"

/**
 * The signing ceremony form - comment (optional, same 2000-char cap as a
 * plain approval), password (step-up re-authentication, never persisted
 * past the request - see `SignAndApproveDialog`'s own doc comment), an
 * explicit consent checkbox, and the cosmetic visual signature (typed name
 * or a canvas drawing - exactly one, matching `visualKind`; never part of
 * what's actually verified, see `UserVisualSignatureService`'s own doc
 * comment on the backend). `meaning` is always "approved" for this dialog
 * (sign-and-approve IS an approval) - not a field the signer picks.
 * <p>
 * No OTP field here: the backend's step-up check (`StepUpAuthenticator`)
 * still asks Keycloak for one when a signer happens to have MFA enabled -
 * this form just doesn't collect it, since nobody in this deployment has
 * MFA turned on yet. That one case would surface as a generic "password or
 * authenticator code incorrect" error rather than a dedicated OTP field -
 * acceptable for now; revisit if/when MFA is actually rolled out.
 * <p>
 * `visualDrawnImageBase64`/`visualUploadedImageBase64` are deliberately TWO
 * separate fields, not one shared "the image" slot - switching between
 * Draw and Upload must never show one editor's content inside the other
 * (they used to share a field, which did exactly that: an uploaded photo
 * would reappear inside the hand-drawing canvas on switching tabs). Only
 * the one matching `visualKind` is ever sent to the backend.
 */
export const signAndApproveSchema = z
  .object({
    comment: z.string().trim().max(2000, "Keep it under 2000 characters"),
    password: z.string().min(1, "Enter your password"),
    consent: z.boolean().refine((value) => value === true, "Confirm you intend to sign this document"),
    visualKind: z.enum(["typed", "drawn", "uploaded"]),
    visualText: z.string().trim().max(150, "Keep it under 150 characters"),
    visualDrawnImageBase64: z.string().nullable(),
    visualUploadedImageBase64: z.string().nullable(),
  })
  .superRefine((values, ctx) => {
    if (values.visualKind === "typed" && values.visualText.length === 0) {
      ctx.addIssue({ code: "custom", path: ["visualText"], message: "Type your name for your signature" })
    }
    if (values.visualKind === "drawn" && !values.visualDrawnImageBase64) {
      ctx.addIssue({ code: "custom", path: ["visualDrawnImageBase64"], message: "Draw your signature" })
    }
    if (values.visualKind === "uploaded" && !values.visualUploadedImageBase64) {
      ctx.addIssue({
        code: "custom",
        path: ["visualUploadedImageBase64"],
        message: "Upload an image of your signature",
      })
    }
  })
export type SignAndApproveFormValues = z.infer<typeof signAndApproveSchema>
