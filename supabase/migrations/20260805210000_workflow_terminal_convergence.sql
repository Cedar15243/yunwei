-- V9 workflow terminal convergence.
-- Device reports may advance delivery/ready/active states, but only a completed
-- execution and its completion step can complete an assignment. Terminal state
-- changes also converge the ordinary maintenance task and work order in one DB
-- transaction so the management web cannot show split truth.

create or replace function public.guard_workflow_assignment_terminal_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.status = 'completed'
     and old.status is distinct from 'completed'
     and new.mode <> 'none' then
    if not exists (
      select 1
      from public.workflow_executions execution
      where execution.organization_id = new.organization_id
        and execution.assignment_id = new.id
        and execution.status = 'completed'
        and exists (
          select 1
          from public.workflow_step_executions step,
            public.workflow_versions version,
            jsonb_array_elements(version.execution_package -> 'nodes') node
          where step.organization_id = execution.organization_id
            and step.execution_id = execution.id
            and step.status = 'completed'
            and version.organization_id = execution.organization_id
            and version.id = execution.workflow_version_id
            and node ->> 'nodeId' = step.node_id
            and node ->> 'type' = 'complete'
        )
    ) then
      raise exception 'workflow assignment completion requires a completed execution';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists workflow_assignments_terminal_guard
on public.workflow_assignments;

create trigger workflow_assignments_terminal_guard
before update of status on public.workflow_assignments
for each row
execute function public.guard_workflow_assignment_terminal_state();

create or replace function public.sync_workflow_execution_terminal_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  event_key text;
  resolved_failure_reason text;
begin
  if tg_op <> 'UPDATE' or new.status is not distinct from old.status then
    return new;
  end if;

  if new.status = 'completed' then
    update public.maintenance_tasks
    set status = 'completed',
        completed_at = coalesce(completed_at, now()),
        updated_at = now()
    where organization_id = new.organization_id
      and id = new.task_id
      and status = 'active';

    update public.work_orders
    set status = 'completed',
        completed_at = coalesce(completed_at, now()),
        updated_at = now()
    where organization_id = new.organization_id
      and id = new.work_order_id
      and status in ('received', 'accepted', 'in_progress');

    event_key := 'workflow-execution:' || new.id::text || ':task_completed';
    insert into public.task_events (
      organization_id,
      task_id,
      actor_profile_id,
      device_id,
      idempotency_key,
      event_type,
      payload
    ) values (
      new.organization_id,
      new.task_id,
      new.operator_profile_id,
      new.device_id,
      event_key,
      'task_completed',
      jsonb_build_object(
        'source', 'workflow_execution',
        'executionId', new.id,
        'assignmentId', new.assignment_id,
        'workOrderId', new.work_order_id
      )
    )
    on conflict (task_id, idempotency_key) do nothing;
  elsif new.status = 'blocked' then
    select nullif(btrim(step.failure_reason), '')
    into resolved_failure_reason
    from public.workflow_step_executions step
    where step.organization_id = new.organization_id
      and step.execution_id = new.id
      and step.status = 'failed'
    order by step.completed_at desc nulls last, step.updated_at desc
    limit 1;
    resolved_failure_reason := coalesce(resolved_failure_reason, 'workflow execution failed');

    update public.maintenance_tasks
    set status = 'aborted',
        updated_at = now()
    where organization_id = new.organization_id
      and id = new.task_id
      and status = 'active';

    update public.work_orders
    set status = 'cancelled',
        updated_at = now()
    where organization_id = new.organization_id
      and id = new.work_order_id
      and status not in ('completed', 'closed', 'cancelled');

    event_key := 'workflow-execution:' || new.id::text || ':task_closed';
    insert into public.task_events (
      organization_id,
      task_id,
      actor_profile_id,
      device_id,
      idempotency_key,
      event_type,
      payload
    ) values (
      new.organization_id,
      new.task_id,
      new.operator_profile_id,
      new.device_id,
      event_key,
      'task_closed',
      jsonb_build_object(
        'source', 'workflow_execution',
        'executionId', new.id,
        'assignmentId', new.assignment_id,
        'workOrderId', new.work_order_id,
        'reason', resolved_failure_reason
      )
    )
    on conflict (task_id, idempotency_key) do nothing;

    update public.workflow_assignments assignment
    set status = 'failed',
        failed_at = coalesce(failed_at, now()),
        failure_stage = coalesce(failure_stage, 'workflow_execution'),
        failure_reason = coalesce(assignment.failure_reason, resolved_failure_reason)
    where assignment.organization_id = new.organization_id
      and assignment.id = new.assignment_id
      and assignment.status not in ('completed', 'failed', 'revoked');
  end if;

  return new;
end;
$$;

drop trigger if exists workflow_executions_terminal_sync
on public.workflow_executions;

create trigger workflow_executions_terminal_sync
after update of status on public.workflow_executions
for each row
execute function public.sync_workflow_execution_terminal_state();

revoke all on function public.guard_workflow_assignment_terminal_state() from public, anon, authenticated;
revoke all on function public.sync_workflow_execution_terminal_state() from public, anon, authenticated;
