import type { UserRecord } from "./types"

/**
 * Ported directly from dms-admin-users.html's ALL_USERS. Swap for a
 * fetch/query hook once the User Management endpoints exist — see
 * components/roles/data.ts for the same "mock now, real API later" note.
 */
export const initialUsers: UserRecord[] = [
  { id: "u-1", name: "Priyanka Fernando", username: "priyanka.fernando", email: "priyanka.fernando@oakdms.com", roles: ["Admin"], status: "active", lastLogin: "2026-08-24" },
  { id: "u-2", name: "Nadia Rajapaksa", username: "nadia.rajapaksa", email: "nadia.rajapaksa@oakdms.com", roles: ["Compliance Officer"], status: "active", lastLogin: "2026-08-23" },
  { id: "u-3", name: "Kasun Perera", username: "kasun.perera", email: "kasun.perera@oakdms.com", roles: ["Approver", "Uploader"], status: "active", lastLogin: "2026-08-22" },
  { id: "u-4", name: "Malik Silva", username: "malik.silva", email: "malik.silva@oakdms.com", roles: ["Approver"], status: "inactive", lastLogin: "2026-07-11" },
  { id: "u-5", name: "Dilani Wickramasinghe", username: "dilani.wickrama", email: "dilani.wickrama@oakdms.com", roles: ["Uploader"], status: "active", lastLogin: "2026-08-20" },
  { id: "u-6", name: "Ruwan Jayasuriya", username: "ruwan.jayasuriya", email: "ruwan.jayasuriya@oakdms.com", roles: ["Viewer"], status: "active", lastLogin: "2026-08-19" },
  { id: "u-7", name: "Anjali Gunawardena", username: "anjali.guna", email: "anjali.guna@oakdms.com", roles: ["Compliance Officer", "Viewer"], status: "inactive", lastLogin: "2026-06-02" },
  { id: "u-8", name: "Thilina Bandara", username: "thilina.bandara", email: "thilina.bandara@oakdms.com", roles: ["Uploader"], status: "active", lastLogin: "2026-08-18" },
  { id: "u-9", name: "Chamari Rathnayake", username: "chamari.rathnayake", email: "chamari.rathnayake@oakdms.com", roles: ["Admin"], status: "active", lastLogin: "2026-08-17" },
  { id: "u-10", name: "Suresh Kumar", username: "suresh.kumar", email: "suresh.kumar@oakdms.com", roles: ["Viewer"], status: "active", lastLogin: "—" },
  { id: "u-11", name: "Ishara De Silva", username: "ishara.desilva", email: "ishara.desilva@oakdms.com", roles: ["Approver"], status: "active", lastLogin: "2026-08-15" },
  { id: "u-12", name: "Nimal Herath", username: "nimal.herath", email: "nimal.herath@oakdms.com", roles: ["Uploader", "Viewer"], status: "inactive", lastLogin: "2026-05-30" },
]