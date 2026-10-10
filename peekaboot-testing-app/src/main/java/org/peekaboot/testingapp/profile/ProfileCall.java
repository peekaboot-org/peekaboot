package org.peekaboot.testingapp.profile;

import java.util.Optional;

/** What a person page asks the profile service for; each go-httpbin endpoint shows the dashboard something else. */
public enum ProfileCall {

    /** Echoes the request back, the traceparent header included. */
    LOOKUP("/anything/profiles/{id}?email={email}"),

    /** Answers after 1.2 s, which takes the page past the Slow bucket's 1000 ms threshold. */
    SLOW("/delay/1.2?email={email}"),

    /** Always answers 503. */
    UNAVAILABLE("/status/503");

    private final String uriTemplate;

    ProfileCall(String uriTemplate) {

        this.uriTemplate = uriTemplate;
    }

    String uriTemplate() {

        return uriTemplate;
    }

    /** The call a person page's {@code profile} parameter names: absent is a plain lookup, an unknown name none. */
    public static Optional<ProfileCall> fromParameter(String parameter) {

        if (parameter == null) {
            return Optional.of(LOOKUP);
        }
        return switch (parameter) {
            case "slow" -> Optional.of(SLOW);
            case "unavailable" -> Optional.of(UNAVAILABLE);
            default -> Optional.empty();
        };
    }
}
