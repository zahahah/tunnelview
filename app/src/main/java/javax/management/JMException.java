package javax.management;

/**
 * Minimal subset of the JMX {@code JMException} hierarchy required by Apache MINA
 * when running on Android, where the javax.management package is not present.
 *
 * This implementation only stores the detail message and extends {@link Exception}
 * so the calling code can unwrap the root cause as expected.
 */
public class JMException extends Exception {
    public JMException() {
        super();
    }

    public JMException(String message) {
        super(message);
    }
}
