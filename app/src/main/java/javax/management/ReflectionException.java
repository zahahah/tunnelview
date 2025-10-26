package javax.management;

/**
 * Minimal stand-in for the JMX {@code ReflectionException} so that libraries depending
 * on the JMX API (not available on Android) can operate without a full implementation.
 */
public class ReflectionException extends JMException {
    private final Exception exception;

    public ReflectionException(Exception exception) {
        super();
        this.exception = exception;
    }

    public ReflectionException(Exception exception, String message) {
        super(message);
        this.exception = exception;
    }

    public Exception getTargetException() {
        return exception;
    }

    @Override
    public synchronized Throwable getCause() {
        return exception;
    }
}
