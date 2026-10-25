package dev.agentideate;

/** The version stamped into the jar manifest at build time; {@code dev} when run from classes. */
public final class Version {

    private Version() {
    }

    public static String current() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
