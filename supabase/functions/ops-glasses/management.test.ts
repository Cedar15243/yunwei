import { assertEquals, assertStringIncludes } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { routeManagement, type ManagementGateway } from "./management.ts";

const identity = { id: "user-a", organizationId: "org-a", role: "ops_admin" as const, displayName: "王工" };

function gateway(): ManagementGateway {
  return {
    authenticate: async (token) => token === "valid-token" ? identity : null,
    dashboard: async () => ({ activeTaskCount: 1, onlineDeviceCount: 1, failedMediaCount: 0, recentTasks: [] }),
    projects: async () => [{ id: "project-a", title: "实训室" }],
    tasks: async () => ({ items: [{ id: "task-a", title: "温湿度异常", status: "active" }], nextCursor: null }),
    taskDetail: async (_identity, taskId) => taskId === "task-a" ? {
      task: { id: "task-a", title: "温湿度异常" },
      events: [],
      messages: [],
      steps: [],
      media: [{ id: "media-a", kind: "photo", url: "https://signed.example/photo.jpg", canRetryMedia: true }],
    } : null,
    retryMedia: async () => true,
  };
}

Deno.test("rejects task lists without bearer authentication", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks"), gateway());
  assertEquals(response.status, 401);
});

Deno.test("returns only authenticated organization tasks", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { items: [{ id: "task-a", title: "温湿度异常", status: "active" }], nextCursor: null });
});

Deno.test("returns signed media only for an authorized task", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks/task-a", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const body = await response.json();
  assertStringIncludes(body.media[0].url, "signed.example");
});
