package org.peekaboot.testingapp;

/**
 * Builds the failure {@code DigestFixtureController} throws. It sits under the application's own
 * package and the exception's stack is taken where it is built, so this frame is the failure's top
 * application frame, the one the error digest keys on. A plain class, so the component scan makes no
 * bean of it.
 */
public final class DigestFailure {

    private DigestFailure() {}

    public static IllegalStateException failure(String tag) {
        return new IllegalStateException("digest fixture " + tag);
    }
}
