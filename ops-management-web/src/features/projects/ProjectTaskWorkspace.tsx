import { ClipboardList, FolderClock } from "lucide-react";
import { useState } from "react";
import type { ManagementApi } from "../../api/management-api";
import { TaskListPage } from "../tasks/TaskListPage";
import { ProjectRecordsPage } from "./ProjectRecordsPage";

export function ProjectTaskWorkspace({ api, onOpenTask }: { api: ManagementApi; onOpenTask: (taskId: string) => void }) {
  const [view, setView] = useState<"tasks" | "projects">("tasks");
  return <section className="page-stack project-task-workspace">
    <div aria-label="项目与任务视图" className="project-task-tabs" role="tablist">
      <button aria-selected={view === "tasks"} onClick={() => setView("tasks")} role="tab" type="button"><ClipboardList size={16} />任务记录</button>
      <button aria-selected={view === "projects"} onClick={() => setView("projects")} role="tab" type="button"><FolderClock size={16} />项目记忆</button>
    </div>
    {view === "tasks" ? <TaskListPage api={api} onOpenTask={onOpenTask} /> : <ProjectRecordsPage api={api} onOpenTask={onOpenTask} />}
  </section>;
}
