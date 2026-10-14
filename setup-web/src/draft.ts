import {
  normalizeSnapshot,
  preferredSourceIds,
  sourceIds,
  type ConnectionSnapshot,
} from "./connection";

export const draftKey = "honorebi.connectionDraft.v1";

export function editableSettings(snapshot: ConnectionSnapshot) {
  return {
    sources: snapshot.sources.map(({ id, host, port, httpPort }) => ({
      id,
      host,
      port,
      httpPort,
    })),
    mainSystem: snapshot.mainSystem,
    preferredSource: snapshot.preferredSource,
    edcbRecordPlayMethod: snapshot.edcbRecordPlayMethod,
    cloudflareAccess: {
      clientId: snapshot.cloudflareAccess.clientId,
      clientSecret: snapshot.cloudflareAccess.clientSecret,
    },
    smbServers: snapshot.smbServers.map(
      ({ id, name, ip, port, user, password }) => ({
        id,
        name,
        ip,
        port,
        user,
        password,
      }),
    ),
  };
}

type Editable = ReturnType<typeof editableSettings>;
export type SettingsDraft = { version: 2; base: Editable; current: Editable };
export function createDraft(
  base: ConnectionSnapshot,
  current: ConnectionSnapshot,
): SettingsDraft {
  return {
    version: 2,
    base: editableSettings(base),
    current: editableSettings(current),
  };
}

/** Reapply edited fields only, retaining fresh server fields and password-presence flags. */
export function applyDraft(
  fresh: ConnectionSnapshot,
  value: unknown,
): ConnectionSnapshot {
  if (!value || typeof value !== "object") throw new Error("Invalid draft");
  const record = value as Partial<SettingsDraft> & Partial<Editable>;
  // A tab opened during the first implementation may have the original flat draft.
  const oldFlat = Array.isArray(record.sources);
  const current = oldFlat ? (record as Editable) : record.current;
  const base = oldFlat ? undefined : record.base;
  if (
    !current ||
    !Array.isArray(current.sources) ||
    !Array.isArray(current.smbServers)
  )
    throw new Error("Invalid draft");
  const result = normalizeSnapshot(fresh);
  const changed = (a: unknown, b: unknown) =>
    JSON.stringify(a) !== JSON.stringify(b);
  for (const source of result.sources) {
    const edited = current.sources.find((s) => s.id === source.id);
    const before = base?.sources.find((s) => s.id === source.id);
    for (const key of ["host", "port", "httpPort"] as const) {
      const next = edited?.[key];
      if (typeof next === "string" && changed(next, before?.[key]))
        source[key] = next;
    }
  }
  if (
    (current.mainSystem === null || sourceIds.includes(current.mainSystem)) &&
    changed(current.mainSystem, base?.mainSystem)
  )
    result.mainSystem = current.mainSystem;
  if (
    preferredSourceIds.includes(current.preferredSource) &&
    changed(current.preferredSource, base?.preferredSource)
  )
    result.preferredSource = current.preferredSource;
  if (
    ["DIRECT", "API"].includes(current.edcbRecordPlayMethod) &&
    changed(current.edcbRecordPlayMethod, base?.edcbRecordPlayMethod)
  )
    result.edcbRecordPlayMethod = current.edcbRecordPlayMethod;
  if (current.cloudflareAccess) {
    for (const key of ["clientId", "clientSecret"] as const) {
      const next = current.cloudflareAccess[key];
      if (
        (typeof next === "string" ||
          (key === "clientSecret" && next === null)) &&
        changed(next, base?.cloudflareAccess?.[key])
      ) {
        if (key === "clientId" && typeof next === "string")
          result.cloudflareAccess.clientId = next;
        if (key === "clientSecret") result.cloudflareAccess.clientSecret = next;
      }
    }
  }
  const beforeServers = base?.smbServers ?? [];
  const removed = beforeServers
    .filter((s) => !current.smbServers.some((item) => item.id === s.id))
    .map((s) => s.id);
  result.smbServers = result.smbServers.filter((s) => !removed.includes(s.id));
  for (const edited of current.smbServers) {
    if (typeof edited.id !== "string") continue;
    const before = beforeServers.find((s) => s.id === edited.id);
    let server = result.smbServers.find((s) => s.id === edited.id);
    if (!server) {
      // Do not resurrect an unchanged entry removed on the TV in the meantime.
      if (before && !changed(before, edited)) continue;
      server = {
        id: edited.id,
        name: "",
        ip: "",
        port: "445",
        user: "",
        password: null,
        passwordConfigured: false,
      };
      result.smbServers.push(server);
    }
    for (const key of ["name", "ip", "port", "user", "password"] as const) {
      const next = edited[key];
      if (
        (typeof next === "string" || (key === "password" && next === null)) &&
        changed(next, before?.[key])
      ) {
        if (key === "password") server.password = next;
        else if (typeof next === "string") server[key] = next;
      }
    }
  }
  return result;
}
