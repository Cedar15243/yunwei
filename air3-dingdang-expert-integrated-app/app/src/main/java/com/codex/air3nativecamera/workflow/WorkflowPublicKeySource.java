package com.codex.air3nativecamera.workflow;

import java.security.PublicKey;

public interface WorkflowPublicKeySource {
    PublicKey find(String keyId);
}
