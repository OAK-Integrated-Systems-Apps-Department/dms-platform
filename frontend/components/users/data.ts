import type { UserRecord } from "./types"

/**
 * Mock DMS users — ids and names match `assignedUsersByRole` in
 * `components/roles/data.ts` so the same person appears consistently in
 * Role Details' Assigned Users table and here. Swap for a
 * `GET /admin/realms/{realm}/users` fetch/query hook later.
 */
export const users: UserRecord[] = [
  {
    id: "u-1",
    name: "Priya Raman",
    username: "praman",
    email: "praman@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-2",
    name: "Owen Castillo",
    username: "ocastillo",
    email: "ocastillo@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-3",
    name: "Marta Lindqvist",
    username: "mlindqvist",
    email: "mlindqvist@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-4",
    name: "Daniel Osei",
    username: "dosei",
    email: "dosei@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-5",
    name: "Renata Alves",
    username: "ralves",
    email: "ralves@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-6",
    name: "Hassan Farouk",
    username: "hfarouk",
    email: "hfarouk@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Inactive",
  },
  {
    id: "u-7",
    name: "Ines Dubois",
    username: "idubois",
    email: "idubois@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-8",
    name: "Kenji Watanabe",
    username: "kwatanabe",
    email: "kwatanabe@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-9",
    name: "Sofia Marchetti",
    username: "smarchetti",
    email: "smarchetti@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-10",
    name: "Tomas Nowak",
    username: "tnowak",
    email: "tnowak@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Inactive",
  },
  {
    id: "u-11",
    name: "Grace Odhiambo",
    username: "godhiambo",
    email: "godhiambo@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
  {
    id: "u-12",
    name: "Liam Fitzgerald",
    username: "lfitzgerald",
    email: "lfitzgerald@acmetextiles.com",
    tenant: "Acme Textiles",
    status: "Active",
  },
]
