import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { routeDeviceMemory, type DeviceMemoryCatalog } from "./device-memory.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};

function request(method = "GET", token = "access-a"): Request {
  return new Request("https://ops.example.com/functions/v1/ops-glasses/device-sync/device-memory", {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

function catalog(): DeviceMemoryCatalog {
  return {
    schemaVersion: 1,
    generatedAt: "2026-08-07T00:00:00Z",
    items: [{
      id: "equipment-a",
      system: "暖通",
      brand: "华方",
      model: "HF-DDC-100",
      quantity: 4,
      status: "normal",
      lastInspectionAt: "2026-08-06T10:00:00Z",
      faultCount: 1,
      repairCount: 2,
      keyParameter: "送风 17 C",
      linkedProjects: [{
        projectId: "project-cloud-a",
        localProjectId: "project-a",
        title: "园区空调",
        status: "active",
        taskCount: 2,
      }],
    }],
  };
}

Deno.test("returns only the authenticated device memory catalog", async () => {
  let called = false;
  const response = await routeDeviceMemory(request(), {
    authenticateDevice: async (token) => token === "access-a" ? identity : null,
    listDeviceMemory: async (received) => {
      called = true;
      assertEquals(received, identity);
      return catalog();
    },
  });

  assertEquals(response.status, 200);
  assertEquals(called, true);
  assertEquals((await response.json()).items[0].id, "equipment-a");
});

Deno.test("fails closed when the device session is missing or unbound", async () => {
  const gateway = {
    authenticateDevice: async () => null,
    listDeviceMemory: async () => catalog(),
  };
  assertEquals((await routeDeviceMemory(request("GET", ""), gateway)).status, 401);
  assertEquals((await routeDeviceMemory(request("POST"), gateway)).status, 404);
});

Deno.test("allows a real equipment record without an optional key parameter", async () => {
  const value = catalog();
  value.items[0].keyParameter = "";
  const response = await routeDeviceMemory(request(), {
    authenticateDevice: async () => identity,
    listDeviceMemory: async () => value,
  });
  assertEquals(response.status, 200);
});
