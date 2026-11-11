package dev.ekin.tokenusage.budget;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signs webhook requests the Standard Webhooks way
 * (https://www.standardwebhooks.com/), so receivers can verify them with an
 * existing library:
 *
 * <pre>
 * webhook-id:        the alert id, the same on every retry
 * webhook-timestamp: Unix seconds of this attempt
 * webhook-signature: v1,base64(HMAC-SHA256(key, id + "." + timestamp + "." + body))
 * </pre>
 *
 * The key is the base64 part of a {@code whsec_...} secret. The timestamp is
 * signed too, so a receiver that rejects old timestamps also rejects replays.
 */
public final class WebhookSigner {

	public static final String ID_HEADER = "webhook-id";
	public static final String TIMESTAMP_HEADER = "webhook-timestamp";
	public static final String SIGNATURE_HEADER = "webhook-signature";
	public static final String SECRET_PREFIX = "whsec_";

	private static final int SECRET_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private WebhookSigner() {
	}

	/** A new random secret: {@code whsec_} followed by the base64 of 32 random bytes. */
	public static String newSecret() {
		byte[] key = new byte[SECRET_BYTES];
		RANDOM.nextBytes(key);
		return SECRET_PREFIX + Base64.getEncoder().encodeToString(key);
	}

	/**
	 * The {@code webhook-signature} value for one request. {@code body} must be
	 * the exact bytes that are sent.
	 */
	public static String sign(String secret, String id, long timestamp, byte[] body) {
		if (secret == null || !secret.startsWith(SECRET_PREFIX)) {
			throw new IllegalArgumentException("Webhook secret must start with " + SECRET_PREFIX);
		}
		byte[] key = Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length()));
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
			mac.update(body);
			return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is not available", e);
		}
	}

}
