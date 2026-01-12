package net.osslabz.peekaboot.backend.domain.trace;

public enum RootActionType {
    HTTP_REQUEST("HTTP Request", "globe"),
    SCHEDULED_JOB("Scheduled Job", "clock"),
    MESSAGE_CONSUMER("Message Consumer", "envelope"),
    RPC_CALL("RPC Call", "network"),
    DATABASE("Database", "database"),
    INTERNAL("Internal", "cog"),
    UNKNOWN("Unknown", "question");

    private final String label;
    private final String icon;

    RootActionType(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
