import type {
  PinAudienceInput,
  PinAudienceMode,
  PinMemberDto,
} from "@/lib/pins/api"

/**
 * The audience a pinner is building in the sheet: a mode and the people it names. For `ALL` the people
 * are the ones left out ("select all, then untick"); for `USERS` they are the only ones let in.
 */
export interface AudienceDraft {
  mode: PinAudienceMode
  members: PinMemberDto[]
}

export const EVERYONE: AudienceDraft = { mode: "ALL", members: [] }

/** A short description, for the pinner's own rows: "Everyone", "Everyone except 3", "Only 7 users". */
export function describeAudience(mode: PinAudienceMode, count: number): string {
  if (mode === "ALL") {
    return count === 0 ? "Everyone" : `Everyone except ${count}`
  }
  return count === 1 ? "Only 1 user" : `Only ${count} users`
}

/** Adds a person, once. */
export function addMember(
  draft: AudienceDraft,
  person: PinMemberDto
): AudienceDraft {
  if (draft.members.some((m) => m.userId === person.userId)) return draft
  return { ...draft, members: [...draft.members, person] }
}

export function removeMember(
  draft: AudienceDraft,
  userId: string
): AudienceDraft {
  return { ...draft, members: draft.members.filter((m) => m.userId !== userId) }
}

/**
 * Switching mode clears the people: an exclusion list and an inclusion list mean opposite things, so
 * carrying one over would silently show the pin to exactly the wrong people.
 */
export function switchMode(
  draft: AudienceDraft,
  mode: PinAudienceMode
): AudienceDraft {
  return draft.mode === mode ? draft : { mode, members: [] }
}

/** The server refuses "specific users" with nobody chosen; say so before it has to. */
export function audienceProblem(draft: AudienceDraft): string | null {
  return draft.mode === "USERS" && draft.members.length === 0
    ? 'Tick at least one user, or choose "Don\'t allow" to show it to everyone.'
    : null
}

export function toInput(draft: AudienceDraft): PinAudienceInput {
  return { mode: draft.mode, userIds: draft.members.map((m) => m.userId) }
}

/** Adds or removes one person: the tick box in the user list. */
export function toggleMember(
  draft: AudienceDraft,
  person: PinMemberDto
): AudienceDraft {
  return draft.members.some((m) => m.userId === person.userId)
    ? removeMember(draft, person.userId)
    : addMember(draft, person)
}

/** Ticks several people at once ("Select all"); anyone already ticked stays, once. */
export function addMembers(
  draft: AudienceDraft,
  people: PinMemberDto[]
): AudienceDraft {
  return people.reduce(addMember, draft)
}

export function clearMembers(draft: AudienceDraft): AudienceDraft {
  return draft.members.length === 0 ? draft : { ...draft, members: [] }
}

const users = (n: number) => (n === 1 ? "1 user" : `${n} users`)

/**
 * One plain sentence saying what the draft will do, so the two modes can't be misread. In "Allow" the
 * ticked users are the only ones who see the pin; in "Don't allow" they are the only ones who don't.
 */
export function summarizeAudience(draft: AudienceDraft): string {
  const n = draft.members.length
  if (draft.mode === "USERS") {
    return n === 0
      ? "Nobody can see this pin yet. Tick the users who should."
      : `${users(n)} can see this pin. Everyone else can't.`
  }
  return n === 0
    ? "Everyone can see this pin."
    : `Everyone can see this pin except ${users(n)}.`
}
