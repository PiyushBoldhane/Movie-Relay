package com.piyush.movierelay.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e, HttpServletResponse response) {
        if (e.getStatus().is5xxServerError()) {
            log.warn("API error [{}]: {}", e.getErrorCode(), e.getMessage(), e);
        }
        return respondOrNull(response, e.getStatus(), new ApiError(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e, HttpServletResponse response) {
        return respondOrNull(response, HttpStatus.BAD_REQUEST,
                new ApiError("missing_parameter", "Missing required parameter: " + e.getParameterName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletResponse response) {
        log.warn("Unexpected error", e);
        return respondOrNull(response, HttpStatus.INTERNAL_SERVER_ERROR,
                new ApiError("internal_error", "Something went wrong. Please try again."));
    }

    // A streaming endpoint (e.g. /api/stream/{id}) can fail partway through writing its
    // response body - most commonly because the client (a <video> element seeking to a new
    // position) already aborted the connection. By that point the response is committed with
    // whatever Content-Type the stream started with (e.g. video/x-matroska), and Spring cannot
    // write a JSON error body onto an already-committed response of a different type - trying
    // to anyway just produces a second, noisier exception with no benefit to the client, who is
    // gone already. Returning null here tells Spring MVC to skip body handling entirely.
    private ResponseEntity<ApiError> respondOrNull(HttpServletResponse response, HttpStatus status, ApiError body) {
        if (response.isCommitted()) {
            log.debug("Response already committed (client likely disconnected) - skipping error body for {}", body.error());
            return null;
        }
        return ResponseEntity.status(status).body(body);
    }
}
