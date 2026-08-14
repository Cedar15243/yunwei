# V9 AI Ops Glasses Product Specification

## Product Positioning

V9 is the closed-loop version of the AI operations glasses product: field engineers collect evidence, receive AI or expert guidance, and authorized administrators review project, task, device, media, and audit history from a cloud-hosted management platform.

## Non-Negotiable Boundaries

- Preserve the existing remote expert web application, endpoint, routes, behavior, layout, and visual design. V9 must not modify or redeploy it.
- Preserve the approved glasses home screen, HUD style, layout, button locations, camera workflow, and expert video page. New capability reuses the current HUD language.
- Deploy the operations management web application separately on the cloud server. It uses its own release directory, Docker container, loopback port, Caddy site block, and domain. It never shares the existing expert web process, port, static files, or release action.
- Build V9 with a new `applicationId`, increasing `versionCode`, and V9 `versionName`. Install beside, never over, the stable APK.
- Device tokens, voiceprint templates, provider credentials, and service secrets must not enter source, APK constants, web bundles, logs, or Git.

## 1. Voice Modes and Audio Ownership

1. Keep the existing Xiao Dingdang wake mode. A wake-up authorizes one command or question.
2. Add a mutually exclusive voiceprint listening mode. It does not need the wake phrase. Each completed segment performs 1:1 voiceprint verification before it can invoke a command or reach AI.
3. When listening is off, the glasses are in explanation/communication state: no recording, transcription, upload, or command execution.
4. Existing single-click behavior is unchanged. Double-click enables voiceprint listening; a later double-click disables it.
5. Camera, video recording, and expert calling pause voiceprint listening before audio ownership changes. They never automatically restore it after release.
6. Voiceprint rejection or network failure shows a recoverable status only. It never silently falls back to wake mode or bypasses verification.
7. A unified audio-capture coordinator owns all microphone states. Wake recognition, voiceprint listening, manual speech, camera/video, and expert calling cannot hold the microphone concurrently.
8. The existing HUD provides voiceprint enrollment, re-enrollment, deletion, and authorization explanation. The backend stores opaque template references, consent time/version, status, revocation, verification outcome, failure lockout, bound user/device, and audit data; provider keys and raw biometric material never leave backend controls.
9. Enrollment starts from the authenticated engineer's glasses HUD after authorization acceptance. Three natural Chinese samples of 6-10 seconds create a backend-proxied template, followed by one verification. Only then can the bound device enable listening. Administrators may view status/revoke access but may not enroll another person's voiceprint.
10. Re-enrollment replaces a template only after new enrollment and verification succeed. Deletion requires second confirmation, deletes the backend binding and provider template, revokes verification immediately, and retains audit without raw audio. Three consecutive verification failures lock listening for 60 seconds.

## Account, Identity, Role, and Device Administration

- A single authenticated `userId` is the identity center for web account, glasses operator, device binding, voiceprint profile, project authorization, expert identity, and future Huafang engineer mapping. A voiceprint template is never an account.
- Organizations are the first data boundary. Accounts belong to one organization and have active/disabled state. Disabling removes future access, device attribution, voiceprint authorization, and assignments without deleting historical audit evidence.
- Account lifecycle: invite/create, authenticate, activate, profile update, role/project-scope assignment or revoke, device bind/unbind, identity-provider recovery, disable, and archive. Every administrative action records actor, target, reason, scope, and time.
- Roles: `super_admin` manages organization/accounts/roles/devices/credentials/policy/audit; `ops_admin` manages tasks, knowledge, skills and permitted devices; `field_engineer` operates assigned projects/devices and only their own voiceprint; `remote_expert` accesses invited task evidence/collaboration; `viewer` is explicitly-scoped read-only.
- Authorization is server-side and organization-scoped. Hiding a button is never treated as an access-control rule.
- Personnel management provides account list, organization, active state, roles, project scope, expert state, device bindings, voiceprint status, last verification, lockout state, and audit history. Controlled actions are invite/create, activate/disable, role/scope edit, bind/unbind device, credential revoke, voiceprint authorization revoke, and audit inspection. Revocations/destructive actions require reason and second confirmation.
- Devices records device key/name, assigned account, status, last seen, MDM policy version, token issue/rotate/revoke state, assigned skill manifest version, and sync health. Device tokens display once only at issuance/rotation and are never recoverable.
- Voiceprint management exposes `not_enrolled`, `awaiting_consent`, `enrolling`, `active`, `verification_locked`, `revoked`, and `provider_error`; consent, binding, operation result, and audit are visible, but templates/raw audio are never exposed.
- Browser identity uses identity-provider sessions. Device credentials are separate and cannot impersonate a human or manage accounts. Raw voice exists only in memory during explicit enrollment/verification, then is cleared. Task history contains a transcript only after authorization, never biometric samples.

## 2. Voice Commands and Page Control

- Wake mode retains its one-turn behavior.
- Verified voiceprint listening supports direct registered menu commands such as inspection tasks, AI operations skills, and return home.
- Non-menu speech is always sent to the current AI conversation. Similar wording must not accidentally navigate, close a task, or change listening mode.
- Global commands: return, return home, close current task and return home, and create a new project.
- Voice help changes with page and voice mode and only presents valid phrases.
- Task acceptance, external submission, task close, and expert calls require explicit second confirmation.

## 3. Projects, Tasks, Memory, and Knowledge

- New project or close-task-and-home makes the next substantive input a new task with no previous task context.
- Historical task state is restored only after the user explicitly selects a historical project.
- Each project independently retains device/model details, pictures, video, transcripts, messages, AI replies, confirmed/excluded facts, risks, steps, summary, and audit history.
- Every AI request receives only the active project's summary, confirmed facts, recent turns, and current evidence reference.
- Completed cases enter Huafang knowledge only after human confirmation. A historical conclusion is never automatically applied to a new project.

## 4. AI Conversation and Repair Guidance

- The first collection may show a short analysis HUD. First result enters full task conversation; later text, voice, photo, and video stay in that task.
- Each user transcript/text/evidence and its AI answer remain paired and attributable.
- AI answers are natural evidence-aware guidance, not a fixed diagnosis/confidence/eight-section template.
- Content fitting one screen stays whole. Overflow paginates at paragraphs, lists, or repair steps. Page navigation and repair-next are separate operations.
- Task actions: next step, complete, abnormal, retake evidence, re-analyze, call expert. No text may overlap or be covered by page controls or the voice HUD.

## 5. Navigation and HUD

- All pages support return and return home. Return home keeps a resumable project; close task ends active context.
- The menu opens on the first press and closes on the second.
- Capability detail follows detail -> capability center -> originating surface or home.
- The home visual remains frozen. Task, listening, and analysis screens keep the same HUD language and do not become a second UI system.

## 6. Existing Capability Pages

- Voice help is page- and mode-aware.
- Glasses tutorial is illustrated, voice accessible, and has a return route.
- Lab inspection and multi-industry demonstration tasks are executable flows, not text-only pages.
- Huafang knowledge base exposes connection state, system catalog, and paged contents.
- Device memory exposes device list, brand, quantity, and linked history.
- AI operations skills lists skills and authorization states and supports voice open/enable/disable. Product UI never calls it Agent Center.
- Expert collaboration remains an isolated, visually consistent video page and returns to the originating project and repair step.

## 7. Operations Platform and Cloud Delivery

- Morning foundation is retained: organization/role/device/project/task/event/media/audit schema and RLS; authorized management API; React/Vite workstation; Docker/Nginx/Caddy deployment skeleton.
- Device tokens are administrator-issued one-time credentials. Cloud stores hashes only and derives organization, device, and actor server-side.
- Android receives the HTTPS endpoint and device token from MDM `RestrictionsManager`. Missing configuration turns off sync only and leaves all local glasses behavior unchanged.
- Device events are local-first, idempotent, and asynchronously synchronized after successful local actions.
- Media transfer is queued after local persistence. Server-authorized short-lived uploads/downloads keep every asset task-scoped.
- Management web is deployed to an isolated cloud directory such as `/srv/dingdang-ops-management-web`, dedicated subdomain/Caddy block, loopback port, Docker container, release artifact, health check, and rollback pointer.

### Cloud records center

- The cloud platform is the authoritative operational record, not merely an event viewer. It provides organization-scoped records for people, roles, glasses devices, device bindings, projects, tasks, event timeline, messages/transcripts, AI responses, photos, videos, expert sessions, confirmations, exclusions, risks, repair steps, summaries, sync failures, and audit events.
- Administrators can search, filter, page, export authorized records, inspect the immutable timeline, locate unsynchronized/failed evidence, and recover by retrying the smallest failed operation. Destructive operations are role-gated, audited, and retain the required evidence/history policy.
- Project, task, media, knowledge, skill, device, and audit records remain organization-scoped through RLS. The web app must never expose another organization through client filters alone.

### Huafang knowledge base

- The cloud platform includes a managed Huafang knowledge base rather than a read-only placeholder. It supports system catalog, folders/categories, documents, attachments, document metadata, search, paged reading, and record-to-knowledge references.
- Authorized roles can create and edit drafts, attach evidence/references, request review, approve/reject, publish, archive, restore, and view a document's version and audit history. Published material is immutable; later edits create a new revision.
- A human-confirmed completed project may be converted into a draft knowledge case with its source task, evidence references, reviewer, applicability, risk notes, and redaction state. No case becomes published knowledge or AI truth automatically.
- Retrieval exposes only published, organization-authorized, applicable references. A reference is labelled as a source for AI/engineer review and cannot silently overwrite a current task's diagnosis or repair state.

### AI operations skill management

- The cloud platform includes a skill library for AI operations skill packages. Each skill has an identity, name, system/category, description, authorization policy, supported evidence/commands, structured configuration, status, release notes, versions, assignments, and audit history.
- Authorized administrators edit a draft skill package and its structured configuration; reviewers approve it; publishing creates an immutable version. Rollback assigns an earlier published version and is auditable.
- Skills can reference only published knowledge documents and explicit evidence schemas. They cannot introduce arbitrary executable code, hidden prompts, provider credentials, or unsafe remote commands into the glasses.
- The glasses fetch only the published skill versions assigned to their organization/device/user. It caches the last valid version for offline continuity and validates schema/version/signature before activation.

### Cloud-to-glasses linkage

- The cloud exposes a device-authorized read contract for skill assignments, published skill configurations, permitted knowledge indexes, feature policy, and version manifest. It uses server-derived organization/device identity, scoped fields, expiry/version metadata, and ETag-style conditional retrieval.
- Publishing or rolling back a knowledge/skill version takes effect at the next safe foreground synchronization point. It never mutates an active task silently: the task stores the applied skill/knowledge version and keeps that snapshot until the engineer explicitly starts a new analysis or accepts an update.
- The glasses upload project/task/evidence events through the existing local-first queue and pull cloud-approved knowledge/skill manifests separately. Either direction failing must preserve local work and provide a visible, recoverable status.
- Management UI editing uses the existing Product Design context and audit. It is a separate management workbench and does not change the remote expert web experience or glasses home HUD.

## 8. Verification and Release Gates

- Deno tests, Edge Function type checking, migration validation, and web build/typecheck/tests must pass.
- Android TDD covers event queue persistence/retry, audio ownership, voice-mode exclusion, verification gate, command classification, project isolation, pagination, and task states.
- HUD freeze and visual checks prove home/expert surfaces have not drifted.
- Air3 evidence covers home first turn, continuous task dialogue, photo plus voice, image-only send, pagination, return, new project, expert collaboration, both voice modes, rejection, and audio contention.
- V9 cloud release validates the separate container/Caddy configuration, authenticated data isolation, and the unchanged health of the existing expert site.
- Release is blocked until migration/Edge Function deployment, MDM enrollment/token provisioning, voiceprint provider authorization, and current Air3 verification are complete.
