// @vitest-environment node
import { describe, expect, it } from "vitest"

import {
  EVERYONE,
  addMember,
  addMembers,
  audienceProblem,
  clearMembers,
  describeAudience,
  removeMember,
  summarizeAudience,
  switchMode,
  toInput,
  toggleMember,
} from "./audience"

const ana = { userId: "u1", displayName: "Ana", email: "ana@example.test" }
const ben = { userId: "u2", displayName: "Ben", email: "ben@example.test" }

describe("describeAudience", () => {
  it("says who sees it", () => {
    expect(describeAudience("ALL", 0)).toBe("Everyone")
    expect(describeAudience("ALL", 3)).toBe("Everyone except 3")
    expect(describeAudience("USERS", 1)).toBe("Only 1 user")
    expect(describeAudience("USERS", 7)).toBe("Only 7 users")
  })
})

describe("building an audience", () => {
  it("adds a person once and removes them again", () => {
    const once = addMember(EVERYONE, ana)
    expect(addMember(once, ana).members).toHaveLength(1)
    expect(removeMember(addMember(once, ben), "u1").members).toEqual([ben])
  })

  it("clears the people when the mode changes, because the two lists mean opposite things", () => {
    const except = addMember(EVERYONE, ana)
    expect(switchMode(except, "USERS")).toEqual({ mode: "USERS", members: [] })
    expect(switchMode(except, "ALL")).toBe(except)
  })

  it("refuses specific users with nobody chosen, but allows everyone with nobody left out", () => {
    expect(audienceProblem({ mode: "USERS", members: [] })).not.toBeNull()
    expect(audienceProblem({ mode: "USERS", members: [ana] })).toBeNull()
    expect(audienceProblem(EVERYONE)).toBeNull()
  })

  it("sends only the mode and the ids", () => {
    expect(toInput({ mode: "ALL", members: [ana, ben] })).toEqual({
      mode: "ALL",
      userIds: ["u1", "u2"],
    })
  })
})

describe("ticking users in the list", () => {
  it("toggles one person on and off", () => {
    const ticked = toggleMember(EVERYONE, ana)
    expect(ticked.members).toEqual([ana])
    expect(toggleMember(ticked, ana).members).toEqual([])
  })

  it("selects several at once without duplicating anyone, and clears them", () => {
    const some = addMembers(addMember(EVERYONE, ana), [ana, ben])
    expect(some.members).toEqual([ana, ben])
    expect(clearMembers(some).members).toEqual([])
    expect(clearMembers(EVERYONE)).toBe(EVERYONE)
  })
})

describe("summarizeAudience", () => {
  it("says what Allow does: only the ticked users see it", () => {
    expect(summarizeAudience({ mode: "USERS", members: [] })).toMatch(
      /Nobody can see/
    )
    expect(summarizeAudience({ mode: "USERS", members: [ana] })).toBe(
      "1 user can see this pin. Everyone else can't."
    )
    expect(summarizeAudience({ mode: "USERS", members: [ana, ben] })).toBe(
      "2 users can see this pin. Everyone else can't."
    )
  })

  it("says what Don't allow does: only the ticked users are kept out", () => {
    expect(summarizeAudience(EVERYONE)).toBe("Everyone can see this pin.")
    expect(summarizeAudience({ mode: "ALL", members: [ana, ben] })).toBe(
      "Everyone can see this pin except 2 users."
    )
  })
})
