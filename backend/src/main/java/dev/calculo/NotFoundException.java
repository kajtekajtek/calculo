package dev.calculo;

/** Missing resource; rendered as 404 with a {"message": ...} body. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
