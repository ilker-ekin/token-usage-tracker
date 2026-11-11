package dev.ekin.tokenusage.budget;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignerTest {

	@Test
	void matchesTheStandardWebhooksTestVector() {
		// The example from the Standard Webhooks specification, so receivers'
		// libraries accept what this signer produces
		String signature = WebhookSigner.sign("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw",
				"msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L,
				"{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8));

		assertThat(signature).isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
	}

	@Test
	void anyChangeToTheIdTimestampOrBodyChangesTheSignature() {
		String secret = WebhookSigner.newSecret();
		byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
		String original = WebhookSigner.sign(secret, "id-1", 100L, body);

		assertThat(WebhookSigner.sign(secret, "id-2", 100L, body)).isNotEqualTo(original);
		assertThat(WebhookSigner.sign(secret, "id-1", 101L, body)).isNotEqualTo(original);
		assertThat(WebhookSigner.sign(secret, "id-1", 100L, "{\"a\":2}".getBytes(StandardCharsets.UTF_8)))
				.isNotEqualTo(original);
		assertThat(WebhookSigner.sign(WebhookSigner.newSecret(), "id-1", 100L, body)).isNotEqualTo(original);
	}

	@Test
	void newSecretsAre32RandomBytesWithThePrefix() {
		String a = WebhookSigner.newSecret();
		String b = WebhookSigner.newSecret();

		assertThat(a).startsWith("whsec_").isNotEqualTo(b);
		assertThat(Base64.getDecoder().decode(a.substring("whsec_".length()))).hasSize(32);
	}

	@Test
	void refusesASecretWithoutThePrefix() {
		assertThatThrownBy(() -> WebhookSigner.sign("not-a-secret", "id", 1L, new byte[0]))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
