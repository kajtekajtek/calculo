package dev.calculo;

/** Invalid client input; rendered as 400 with a {"message": ...} body. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
