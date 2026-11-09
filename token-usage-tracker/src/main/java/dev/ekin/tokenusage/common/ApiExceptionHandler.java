package dev.ekin.tokenusage.common;

import dev.ekin.tokenusage.pricing.PricingCatalogUnavailableException;
import dev.ekin.tokenusage.pricing.PricingNotFoundException;
import dev.ekin.tokenusage.tenant.ProjectNotFoundException;
import dev.ekin.tokenusage.usage.InvalidUsageEventException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(BadCredentialsException.class)
	ProblemDetail handleBadCredentials(BadCredentialsException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
	}

	@ExceptionHandler(PricingNotFoundException.class)
	ProblemDetail handlePricingNotFound(PricingNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

	/** Catalog outage, not a bad request: the client may retry unchanged. */
	@ExceptionHandler(PricingCatalogUnavailableException.class)
	ProblemDetail handlePricingCatalogUnavailable(PricingCatalogUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}

	@ExceptionHandler(InvalidUsageEventException.class)
	ProblemDetail handleInvalidUsageEvent(InvalidUsageEventException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	/** Names every rejected field, so a client can tell which value to fix. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> errors = new TreeMap<>();
		for (FieldError error : e.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Invalid request: " + String.join(", ", errors.keySet()));
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	ProblemDetail handleProjectNotFound(ProjectNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

}