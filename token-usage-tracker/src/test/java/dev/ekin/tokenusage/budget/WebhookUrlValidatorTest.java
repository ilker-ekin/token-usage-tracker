package dev.ekin.tokenusage.budget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Literal IP addresses throughout, so no test depends on DNS. */
class WebhookUrlValidatorTest {

	private final WebhookUrlValidator strict = new WebhookUrlValidator(false);

	@Test
	void acceptsHttpsOnAPublicAddress() {
		assertThatCode(() -> strict.validate("https://8.8.8.8/hooks/budget")).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://8.8.8.8/hook",          // not https
			"ftp://8.8.8.8/hook",
			"https://127.0.0.1/hook",       // loopback
			"https://[::1]/hook",
			"https://10.0.0.5/hook",        // private ranges
			"https://172.16.0.1/hook",
			"https://192.168.1.10/hook",
			"https://169.254.169.254/latest/meta-data", // cloud metadata (link-local)
			"https://100.64.0.1/hook",      // carrier-grade NAT
			"https://[fd00::1]/hook",       // IPv6 unique local
			"https://0.0.0.0/hook",
			"https://user:pw@8.8.8.8/hook", // credentials in the URL
			"/relative/path",
			"not a url"})
	void rejects(String url) {
		assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(InvalidWebhookUrlException.class);
	}

	@Test
	void insecureModeAllowsLocalHttpButStillRequiresAnHttpUrl() {
		WebhookUrlValidator lenient = new WebhookUrlValidator(true);

		assertThatCode(() -> lenient.validate("http://127.0.0.1:9000/hook")).doesNotThrowAnyException();
		assertThatThrownBy(() -> lenient.validate("ftp://127.0.0.1/hook"))
				.isInstanceOf(InvalidWebhookUrlException.class);
	}

}
