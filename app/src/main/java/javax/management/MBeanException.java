package javax.management;

/**
 * Minimal replacement for {@code javax.management.MBeanException} used by Apache MINA.
 * Stores the original cause and behaves like the JMX class for compatibility.
 */
public class MBeanException extends JMException {
    private final Exception exception;

    public MBeanException(Exception exception) {
        super();
        this.exception = exception;
    }

    public MBeanException(Exception exception, String message) {
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
