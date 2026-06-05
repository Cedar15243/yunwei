# Server SSH Console Recovery Glasses MVP Design

## Goal

Build a second-stage internal operations MVP that proves AI glasses can solve an offline server problem that online AI cannot touch.

The selected scenario is: **a Linux server is unreachable by SSH, and a non-expert onsite operator uses AI glasses to recover access through the local console**.

## Value Thesis

Online AI is useful for remote server operations while the server is reachable. Once SSH is down, remote AI cannot run commands on the machine.

An onsite junior operator can stand in front of the server, but may not understand Linux console output, network commands, emergency mode messages, or safe recovery steps.

The AI glasses bridge the gap:

- The glasses show the local console to AI.
- AI reads the screen and combines it with remote network probes.
- AI gives short, safe steps to the onsite operator.
- The backend verifies recovery by re-running `ping` and SSH checks.

## Demo Scenario

The server is reachable by local keyboard/monitor but unreachable remotely.

Possible simulated causes:

- network interface is down,
- static IP is missing or wrong,
- default route is missing,
- SSH service is stopped,
- system is in a limited/emergency state.

Recommended first demo cause:

```text
SSH service stopped or network interface down on a Linux test machine.
```

This is safe, reversible, and visible through both remote probes and local console commands.

## Phase-2 MVP Scope

### In Scope

- Reuse the existing Air3 image upload and text display path.
- Create one server recovery session.
- Simulate or run remote probes:
  - ping target,
  - TCP check for port 22,
  - optional HTTP health check if the server has one.
- Guide the onsite operator through:
  - confirming they are at the correct server,
  - photographing the local console,
  - logging in if needed,
  - typing safe diagnostic commands,
  - typing one safe recovery command,
  - confirming output with another photo.
- Verify recovery by checking that SSH port 22 is reachable.
- Log session steps, image metadata, instructions, and final result.

### Out of Scope

- Production recovery without operator confirmation.
- Automatic command execution on the unreachable server.
- Destructive commands such as disk formatting, package removal, or firewall flushes.
- Complex network topology diagnosis.
- Full speech input/output.
- Precise AR overlays.

## Recommended Demo Setup

Use a Linux test server, spare mini PC, VM with visible console, or old laptop.

Recommended demo fixture:

```json
{
  "serverId": "srv-console-demo-01",
  "assetTag": "ASSET-CONSOLE-001",
  "host": "192.168.1.50",
  "sshPort": 22,
  "expectedIssue": "ssh_unreachable",
  "safeRecovery": "restart_ssh_service"
}
```

For a simple first demo, stop SSH on the test server:

```bash
sudo systemctl stop ssh
```

Then let the AI glasses guide the onsite operator to run:

```bash
sudo systemctl status ssh --no-pager
sudo systemctl start ssh
sudo systemctl status ssh --no-pager
```

If the distro uses `sshd`, use:

```bash
sudo systemctl status sshd --no-pager
sudo systemctl start sshd
sudo systemctl status sshd --no-pager
```

## User Flow

1. Backend starts a demo recovery task for `srv-console-demo-01`.
2. Backend probes the server and sees SSH is unreachable.
3. Operator launches the Air3 app and captures the first onsite photo.
4. Backend returns the next instruction.
5. Operator photographs the server label and local console.
6. AI/rule-based MVP identifies the console state and asks for a command.
7. Operator types the command shown on the glasses.
8. Operator photographs command output.
9. Backend asks for the safe recovery command.
10. Operator runs it.
11. Backend rechecks SSH.
12. Glasses show final result: SSH restored or escalate to human.

## Step State Machine

```text
created
  -> remote_probe_failed
  -> locate_server
  -> inspect_console
  -> run_diagnostic_command
  -> confirm_diagnostic_output
  -> run_recovery_command
  -> verify_remote_access
  -> completed
```

Escalation states:

```text
needs_better_photo
needs_human_expert
aborted
```

## Glasses Response Style

Each response should fit the Air3 display:

- one diagnosis line,
- one action line,
- one safety warning when needed,
- one expected photo or confirmation.

Example:

```text
远程 SSH 仍不可达。
请在本地控制台输入：sudo systemctl status ssh --no-pager
只输入这一条命令，不要执行其他操作。
执行后请拍摄完整输出。
```

## Success Criteria

The MVP is successful when:

- the backend detects SSH is unreachable,
- the glasses can upload a real console photo,
- the backend returns staged instructions instead of fixed text,
- the onsite operator can follow instructions without Linux expertise,
- the backend verifies SSH port 22 becomes reachable again,
- the session log records each step and final result.

## Safety Boundaries

- First validation must use a test server, spare device, or VM.
- The recovery command must come from an allowlist.
- AI must not invent arbitrary shell commands in the MVP.
- If the photo is unclear, ask for another photo.
- If the console shows unexpected errors, escalate to a human expert.

