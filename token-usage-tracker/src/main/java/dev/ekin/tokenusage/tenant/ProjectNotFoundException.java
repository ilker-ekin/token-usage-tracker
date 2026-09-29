package dev.ekin.tokenusage.tenant;

import java.util.UUID;

/**
 * Thrown both when a project does not exist and when it belongs to another
 * org, so callers cannot probe for other tenants' project ids.
 */
public class ProjectNotFoundException extends RuntimeException {

	public ProjectNotFoundException(UUID projectId) {
		super("Project not found: " + projectId);
	}

}
