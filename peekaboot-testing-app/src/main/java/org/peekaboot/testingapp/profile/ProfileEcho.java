package org.peekaboot.testingapp.profile;

/** What go-httpbin echoed of the profile request: what actually went over the wire. */
public record ProfileEcho(String method, String url, String email, String traceparent) {}
