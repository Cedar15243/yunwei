package com.codex.air3nativecamera.sync;

import org.json.JSONObject;

import java.io.IOException;

public final class WorkflowAssignmentSyncCoordinator implements WorkflowSyncOperation {
    public enum Code {
        SUCCESS,
        PARTIAL_FAILURE,
        NETWORK_FAILURE,
        REPOSITORY_REJECTED,
        PERSIST_FAILED
    }

    public interface PackageCache {
        boolean isAvailable(WorkflowAssignmentRepository.CachedAssignment assignment);

        boolean install(
                WorkflowAssignmentRepository.CachedAssignment assignment,
                JSONObject envelope
        );

        boolean invalidate(String assignmentId);
    }

    public static final class Result {
        private final Code code;
        private final int packagesInstalled;
        private final int failures;
        private final boolean moreWork;

        Result(Code code, int packagesInstalled, int failures, boolean moreWork) {
            this.code = code;
            this.packagesInstalled = packagesInstalled;
            this.failures = failures;
            this.moreWork = moreWork;
        }

        public Code code() { return code; }
        public int packagesInstalled() { return packagesInstalled; }
        public int failures() { return failures; }
        public boolean moreWork() { return moreWork; }
    }

    private final WorkflowAssignmentGateway gateway;
    private final WorkflowAssignmentRepository repository;
    private final PackageCache packages;
    private final int maxPackageFetches;

    public WorkflowAssignmentSyncCoordinator(
            WorkflowAssignmentGateway gateway,
            WorkflowAssignmentRepository repository,
            PackageCache packages,
            int maxPackageFetches
    ) {
        if (gateway == null || repository == null || packages == null
                || maxPackageFetches < 1 || maxPackageFetches > 20) {
            throw new IllegalArgumentException("workflow assignment sync configuration is invalid");
        }
        this.gateway = gateway;
        this.repository = repository;
        this.packages = packages;
        this.maxPackageFetches = maxPackageFetches;
    }

    @Override
    public Result syncOnce() {
        WorkflowDeviceHttpClient.AssignmentPage page;
        try {
            page = gateway.listAssignments(repository.cursor(), 100);
        } catch (IOException exception) {
            return new Result(Code.NETWORK_FAILURE, 0, 1, true);
        }

        WorkflowAssignmentRepository.ApplyResult applied = repository.apply(page);
        if (applied == WorkflowAssignmentRepository.ApplyResult.PERSIST_FAILED) {
            return new Result(Code.PERSIST_FAILED, 0, 1, true);
        }
        if (applied == WorkflowAssignmentRepository.ApplyResult.REJECTED) {
            return new Result(Code.REPOSITORY_REJECTED, 0, 1, true);
        }

        int fetches = 0;
        int installed = 0;
        int failures = 0;
        boolean moreWork = false;
        for (WorkflowAssignmentRepository.CachedAssignment assignment : repository.assignments()) {
            if (invalidatesPackage(assignment)) {
                if (!packages.invalidate(assignment.assignmentId())) failures += 1;
                continue;
            }

            boolean available = packages.isAvailable(assignment);
            if (available) {
                if (!assignment.packageCached()) {
                    WorkflowAssignmentRepository.ApplyResult marked = repository.markPackageCached(
                            assignment.assignmentId(), assignment.workflowVersionId());
                    if (marked == WorkflowAssignmentRepository.ApplyResult.PERSIST_FAILED) failures += 1;
                    else if (marked == WorkflowAssignmentRepository.ApplyResult.REJECTED) failures += 1;
                }
                continue;
            }
            if (assignment.packageCached()) {
                WorkflowAssignmentRepository.ApplyResult marked = repository.markPackageUnavailable(
                        assignment.assignmentId(), assignment.workflowVersionId());
                if (marked == WorkflowAssignmentRepository.ApplyResult.PERSIST_FAILED
                        || marked == WorkflowAssignmentRepository.ApplyResult.REJECTED) {
                    failures += 1;
                    continue;
                }
            }
            if (fetches >= maxPackageFetches) {
                moreWork = true;
                continue;
            }
            fetches += 1;
            try {
                JSONObject envelope = gateway.fetchPackage(assignment.assignmentId());
                if (!packages.install(assignment, envelope)) {
                    failures += 1;
                    continue;
                }
                WorkflowAssignmentRepository.ApplyResult marked = repository.markPackageCached(
                        assignment.assignmentId(), assignment.workflowVersionId());
                if (marked == WorkflowAssignmentRepository.ApplyResult.APPLIED
                        || marked == WorkflowAssignmentRepository.ApplyResult.NO_CHANGE) {
                    installed += 1;
                } else {
                    failures += 1;
                }
            } catch (IOException | RuntimeException exception) {
                failures += 1;
            }
        }
        return new Result(
                failures == 0 ? Code.SUCCESS : Code.PARTIAL_FAILURE,
                installed,
                failures,
                moreWork);
    }

    private static boolean invalidatesPackage(
            WorkflowAssignmentRepository.CachedAssignment assignment
    ) {
        return "none".equals(assignment.mode())
                || "failed".equals(assignment.status())
                || "revoked".equals(assignment.status());
    }
}
