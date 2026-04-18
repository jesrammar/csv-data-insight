package com.asecon.enterpriseiq.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        if (fe == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Petición inválida.");
        }
        String field = fe.getField() != null ? fe.getField() : "";
        String msg = fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Valor inválido.";

        // Make validation errors user-facing (avoid Spring method signatures in UI).
        if ("password".equalsIgnoreCase(field)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("La contraseña no puede estar vacía.");
        }
        if ("email".equalsIgnoreCase(field)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("El email no puede estar vacío.");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException ex) {
        String body = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleOther(Exception ex) {
        // Keep response short and safe for UI. Details should be in server logs.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno. Inténtalo de nuevo en unos segundos.");
    }
}
