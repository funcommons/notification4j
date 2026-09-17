package fun.commons.notification4j.properties;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// VECTOR: TAG=step1-properties
class NfyPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaults_are_local_and_api_disabled() {
        NfyProperties p = new NfyProperties();
        assertThat(p.getMode()).isEqualTo("local");
        assertThat(p.isEnableApi()).isFalse();
    }

    @Test
    void mode_accepts_local_and_remote() {
        NfyProperties p = new NfyProperties();
        p.setMode("remote");
        assertThat(validator.validate(p)).isEmpty();
        p.setMode("local");
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void mode_rejects_invalid_value() {
        NfyProperties p = new NfyProperties();
        p.setMode("cluster");
        assertThat(validator.validate(p)).isNotEmpty();
    }
}
