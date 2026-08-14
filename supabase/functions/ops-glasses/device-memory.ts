import {
  createDeviceSyncGateway,
  deviceMayAccessProject,
  type DeviceSyncIdentity,
} from "./device-sync.ts";

export type DeviceMemoryProject = {
  projectId: string;
  localProjectId: string;
  title: string;
  status: string;
  taskCount: number;
};

export type DeviceMemoryItem = {
  id: string;
  system: string;
  brand: string;
  model: string;
  quantity: number;
  status: "normal" | "attention" | "maintenance" | "decommissioned";
  lastInspectionAt: string | null;
  faultCount: number;
  repairCount: number;
  keyParameter: string;
  linkedProjects: DeviceMemoryProject[];
};

export type DeviceMemoryCatalog = {
  schemaVersion: 1;
  generatedAt: string;
  items: DeviceMemoryItem[];
};

export type DeviceMemoryGateway = {
  authenticateDevice(token: string): Promise<DeviceSyncIdentity | null>;
  listDeviceMemory(identity: DeviceSyncIdentity): Promise<DeviceMemoryCatalog>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Content-Type": "application/json; charset=utf-8",
};
const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/;
const textPattern = /^[^\u0000-\u001f\u007f]{1,400}$/;
const allowedStatuses = new Set(["normal", "attention", "maintenance", "decommissioned"]);

export function isDeviceMemoryPath(path: string): boolean {
  return (path
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/") === "/device-sync/device-memory";
}

export async function routeDeviceMemory(
  request: Request,
  gateway: DeviceMemoryGateway,
): Promise<Response> {
  const path = routePath(request);
  if (request.method !== "GET" || path !== "/device-sync/device-memory") {
    return response({ ok: false, error: "not_found" }, 404);
  }
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticateDevice(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!identity.actorProfileId) return response({ ok: false, error: "device_not_bound" }, 403);
  const catalog = await gateway.listDeviceMemory(identity);
  const validationError = validateCatalog(catalog);
  if (validationError) return response({ ok: false, error: validationError }, 502, {
    "Cache-Control": "no-store",
  });
  return response({ ok: true, ...catalog }, 200, {
    "Cache-Control": "private, max-age=30, must-revalidate",
  });
}

export function createDeviceMemoryGateway(supabase: any): DeviceMemoryGateway {
  const deviceGateway = createDeviceSyncGateway(supabase);
  return {
    authenticateDevice: deviceGateway.authenticateDevice,
    async listDeviceMemory(identity) {
      const [{ data: equipmentRows, error: equipmentError }, { data: links, error: linksError }] =
        await Promise.all([
          supabase.from("ops_equipment")
            .select("id, equipment_key, system, brand, model, quantity, status, last_inspection_at, fault_count, repair_count, key_parameter")
            .eq("organization_id", identity.organizationId)
            .order("system", { ascending: true })
            .order("equipment_key", { ascending: true }),
          supabase.from("ops_equipment_projects")
            .select("equipment_id, project_id")
            .eq("organization_id", identity.organizationId),
        ]);
      if (equipmentError) throw equipmentError;
      if (linksError) throw linksError;
      const equipment = Array.isArray(equipmentRows) ? equipmentRows : [];
      const relationRows = Array.isArray(links) ? links : [];
      const projectIds = Array.from(new Set(relationRows.map((row: Record<string, unknown>) =>
        String(row.project_id ?? "")).filter(Boolean)));
      if (projectIds.length === 0 || equipment.length === 0) {
        return { schemaVersion: 1, generatedAt: new Date().toISOString(), items: [] };
      }
      const [{ data: projectRows, error: projectError }, { data: taskRows, error: taskError }] =
        await Promise.all([
          supabase.from("ops_projects")
            .select("id, local_project_id, title, status")
            .eq("organization_id", identity.organizationId)
            .in("id", projectIds),
          supabase.from("maintenance_tasks")
            .select("project_id")
            .eq("organization_id", identity.organizationId)
            .in("project_id", projectIds),
        ]);
      if (projectError) throw projectError;
      if (taskError) throw taskError;
      const accessibleProjects = new Map<string, DeviceMemoryProject>();
      const taskCounts = new Map<string, number>();
      for (const row of (Array.isArray(taskRows) ? taskRows : [])) {
        const projectId = String((row as Record<string, unknown>).project_id ?? "");
        if (projectId) taskCounts.set(projectId, (taskCounts.get(projectId) ?? 0) + 1);
      }
      for (const row of (Array.isArray(projectRows) ? projectRows : [])) {
        const projectId = String((row as Record<string, unknown>).id ?? "");
        if (!projectId || !await deviceMayAccessProject(supabase, identity, projectId)) continue;
        accessibleProjects.set(projectId, {
          projectId,
          localProjectId: String((row as Record<string, unknown>).local_project_id ?? ""),
          title: String((row as Record<string, unknown>).title ?? ""),
          status: String((row as Record<string, unknown>).status ?? ""),
          taskCount: taskCounts.get(projectId) ?? 0,
        });
      }
      const linksByEquipment = new Map<string, DeviceMemoryProject[]>();
      for (const row of relationRows) {
        const equipmentId = String((row as Record<string, unknown>).equipment_id ?? "");
        const project = accessibleProjects.get(String((row as Record<string, unknown>).project_id ?? ""));
        if (!equipmentId || !project) continue;
        const values = linksByEquipment.get(equipmentId) ?? [];
        if (!values.some((item) => item.projectId === project.projectId)) values.push(project);
        linksByEquipment.set(equipmentId, values);
      }
      const items: DeviceMemoryItem[] = [];
      for (const row of equipment) {
        const equipmentId = String((row as Record<string, unknown>).id ?? "");
        const linkedProjects = linksByEquipment.get(equipmentId) ?? [];
        if (linkedProjects.length === 0) continue;
        items.push({
          id: String((row as Record<string, unknown>).equipment_key ?? ""),
          system: String((row as Record<string, unknown>).system ?? ""),
          brand: String((row as Record<string, unknown>).brand ?? ""),
          model: String((row as Record<string, unknown>).model ?? ""),
          quantity: Number((row as Record<string, unknown>).quantity ?? 0),
          status: String((row as Record<string, unknown>).status ?? "normal") as DeviceMemoryItem["status"],
          lastInspectionAt: nullableText((row as Record<string, unknown>).last_inspection_at),
          faultCount: Number((row as Record<string, unknown>).fault_count ?? 0),
          repairCount: Number((row as Record<string, unknown>).repair_count ?? 0),
          keyParameter: String((row as Record<string, unknown>).key_parameter ?? ""),
          linkedProjects: linkedProjects.sort((left, right) => left.title.localeCompare(right.title)),
        });
      }
      return { schemaVersion: 1, generatedAt: new Date().toISOString(), items };
    },
  };
}

function validateCatalog(value: DeviceMemoryCatalog): string | null {
  if (!value || value.schemaVersion !== 1 || !Number.isFinite(Date.parse(value.generatedAt)) ||
      !Array.isArray(value.items) || value.items.length > 500) return "device_memory_response_invalid";
  for (const item of value.items) {
    if (!identifierPattern.test(item.id) || !textPattern.test(item.system) ||
        !textPattern.test(item.brand) || !textPattern.test(item.model) ||
        !Number.isInteger(item.quantity) || item.quantity < 0 || item.quantity > 1_000_000 ||
        !allowedStatuses.has(item.status) || (item.lastInspectionAt !== null &&
          !Number.isFinite(Date.parse(item.lastInspectionAt))) ||
        !Number.isInteger(item.faultCount) || item.faultCount < 0 ||
        !Number.isInteger(item.repairCount) || item.repairCount < 0 ||
        (item.keyParameter !== "" && !textPattern.test(item.keyParameter)) || !Array.isArray(item.linkedProjects) ||
        item.linkedProjects.length > 100) return "device_memory_response_invalid";
    for (const project of item.linkedProjects) {
      if (!identifierPattern.test(project.projectId) ||
          !identifierPattern.test(project.localProjectId) ||
          !textPattern.test(project.title) || !textPattern.test(project.status) ||
          !Number.isInteger(project.taskCount) || project.taskCount < 0) {
        return "device_memory_response_invalid";
      }
    }
  }
  return null;
}

function nullableText(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.slice(7).trim() ? value.slice(7).trim() : null;
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function response(body: unknown, status: number, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, ...extraHeaders } });
}
