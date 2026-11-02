package dev.calculo.xtb;

/** The uploaded file is not a readable XTB export. The message is user-facing (Polish). */
public class XtbFormatException extends RuntimeException {
    public XtbFormatException(String message) {
        super(message);
    }

    public XtbFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
