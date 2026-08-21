import io.github.sahiljitsinghmanhas.loggermanager.core.PluginSettings;

/** Exercises the shipped glob matcher directly, rather than by inspection. */
public class GlobTest {

    static int failed = 0;

    static void check(String pattern, String name, boolean want) {
        boolean got = PluginSettings.matches(pattern, name);
        if (got != want) {
            failed++;
            System.out.println("  FAIL  matches(\"" + pattern + "\", \"" + name + "\") = "
                    + got + ", wanted " + want);
        } else {
            System.out.println("  ok    matches(\"" + pattern + "\", \"" + name + "\") = " + got);
        }
    }

    public static void main(String[] args) {
        // A bare name is still exact - protecting "sailpoint" must not protect
        // everything beneath it, or the plugin cannot do its job.
        check("sailpoint", "sailpoint", true);
        check("sailpoint", "sailpoint.api.provisioner", false);
        check("root", "root", true);
        check("root", "rooted", false);

        // Asking for the tree, deliberately.
        check("sailpoint.*", "sailpoint.api.provisioner", true);
        check("sailpoint.*", "sailpoint.connector.ldapconnector", true);
        check("sailpoint.*", "sailpoint", false);
        check("sailpoint.*", "org.hibernate.sql", false);

        // From the other end, and in the middle.
        check("*.provisioner", "sailpoint.api.provisioner", true);
        check("*.provisioner", "sailpoint.api.provisioners", false);
        check("sailpoint.*.provisioner", "sailpoint.api.provisioner", true);
        check("*", "anything.at.all", true);

        // Dots are literal, not regex wildcards: "sailpointxapi" must not match.
        check("sailpoint.api", "sailpointxapi", false);

        // Regex metacharacters in a logger name are data, not syntax.
        check("rule.my(rule)", "rule.my(rule)", true);
        check("rule.my(rule)", "rule.myrule", false);

        // Degenerate input protects nothing rather than throwing.
        check(null, "sailpoint", false);
        check("sailpoint", null, false);
        check("", "", true);

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }
}
