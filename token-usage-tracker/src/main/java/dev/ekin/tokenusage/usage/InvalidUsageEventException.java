package dev.ekin.tokenusage.usage;

/** A usage event that must not be booked; reported to the client as 400. */
public class InvalidUsageEventException extends RuntimeException {

	public InvalidUsageEventException(String message) {
		super(message);
	}

}
