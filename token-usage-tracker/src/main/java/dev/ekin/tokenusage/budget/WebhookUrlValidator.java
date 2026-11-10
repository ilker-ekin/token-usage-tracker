package dev.ekin.tokenusage.budget;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The server calls webhook URLs that an org admin typed in, so a URL must
 * not be able to point it at its own network (cloud metadata endpoints,
 * internal services, localhost). Checked when a budget is saved and again
 * right before every delivery, because the DNS answer can change in between.
 */
@Component
public class WebhookUrlValidator {

	private final boolean allowInsecure;

	@Autowired
	public WebhookUrlValidator(BudgetProperties properties) {
		this(properties.allowInsecureWebhookUrls());
	}

	WebhookUrlValidator(boolean allowInsecure) {
		this.allowInsecure = allowInsecure;
	}

	/** @throws InvalidWebhookUrlException when the URL must not be called */
	public void validate(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			throw new InvalidWebhookUrlException("webhookUrl is not a valid URL");
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null || uri.getUserInfo() != null) {
			throw new InvalidWebhookUrlException("webhookUrl must be an absolute URL without credentials");
		}
		boolean https = scheme.equalsIgnoreCase("https");
		if (!https && !(allowInsecure && scheme.equalsIgnoreCase("http"))) {
			throw new InvalidWebhookUrlException("webhookUrl must use https");
		}
		if (allowInsecure) {
			return;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new InvalidWebhookUrlException("webhookUrl host cannot be resolved");
		}
		for (InetAddress address : addresses) {
			if (isInternal(address)) {
				throw new InvalidWebhookUrlException("webhookUrl must not point to a private or local address");
			}
		}
	}

	private static boolean isInternal(InetAddress address) {
		if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		if (address instanceof Inet6Address) {
			// fc00::/7 unique local addresses, which isSiteLocalAddress does not cover
			return (bytes[0] & 0xfe) == 0xfc;
		}
		// 100.64.0.0/10 carrier-grade NAT, also used for internal ranges by some clouds
		return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
	}

}
