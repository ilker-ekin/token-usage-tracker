package dev.ekin.tokenusage.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param name   cookie that carries the dashboard's JWT.
 * @param secure send the cookie over https only. Turn off only for a plain-http
 *               setup such as the local Docker demo.
 */
@ConfigurationProperties(prefix = "app.auth.session-cookie")
public record SessionCookieProperties(String name, Boolean secure) {

	public SessionCookieProperties {
		name = name != null ? name : "tut_session";
		secure = secure == null || secure;
	}

}
