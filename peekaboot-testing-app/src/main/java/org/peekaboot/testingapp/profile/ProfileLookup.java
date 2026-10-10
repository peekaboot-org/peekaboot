package org.peekaboot.testingapp.profile;

/** The person page's profile section: the echo when the service answered, otherwise why there is none. */
public record ProfileLookup(String outcome, ProfileEcho echo) {

    public static ProfileLookup answered(ProfileEcho echo) {

        return new ProfileLookup("answered", echo);
    }

    public static ProfileLookup unreachable() {

        return new ProfileLookup("unreachable", null);
    }

    public static ProfileLookup notConfigured() {

        return new ProfileLookup("not configured", null);
    }
}
