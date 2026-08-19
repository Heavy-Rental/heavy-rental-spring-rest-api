package com.heavy_rental.rest_api.util;

/**
 * DEVSECOPS DEMO ARTIFACT — NOT FOR COMMIT.
 *
 * Intentionally contains a hardcoded, fake credential so the Security Testing
 * job's Semgrep (p/secrets) pass has a real finding to show live during the
 * HR-195 pipeline walkthrough. This class is never referenced by application
 * code and has no effect on runtime behavior.
 *
 * Remove this file before committing/pushing.
 */
public final class DevSecOpsDemoSecrets {

    private DevSecOpsDemoSecrets() {
    }

    public static final String DEMO_AWS_ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    public static final String DEMO_AWS_SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
}
