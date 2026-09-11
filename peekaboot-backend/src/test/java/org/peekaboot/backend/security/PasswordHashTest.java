package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHashTest {

    @Test
    void matches_acceptsThePasswordItWasBuiltFrom() {
        var hash = PasswordHash.of("correct-horse");

        assertThat(hash.matches("correct-horse")).isTrue();
    }

    @Test
    void matches_rejectsAnyOtherPassword() {
        var hash = PasswordHash.of("correct-horse");

        assertThat(hash.matches("correct-horsf")).isFalse();
        assertThat(hash.matches("")).isFalse();
    }

    @Test
    void of_saltsEveryHashSeparately() {
        assertThat(PasswordHash.of("same").format())
                .isNotEqualTo(PasswordHash.of("same").format());
    }

    @Test
    void parse_roundTripsAFormattedHash() {
        var hash = PasswordHash.of("correct-horse");

        var parsed = PasswordHash.parse(hash.format());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().matches("correct-horse")).isTrue();
        assertThat(parsed.get().format()).isEqualTo(hash.format());
    }

    @Test
    void format_namesTheAlgorithmAndTheIterationCount() {
        assertThat(PasswordHash.of("correct-horse").format()).startsWith("pbkdf2-sha256$210000$");
    }

    @Test
    void parse_rejectsAnythingItDoesNotUnderstand() {
        assertThat(PasswordHash.parse("")).isEmpty();
        assertThat(PasswordHash.parse("plaintext")).isEmpty();
        assertThat(PasswordHash.parse("pbkdf2-sha256$210000$onlythree")).isEmpty();
        assertThat(PasswordHash.parse("bcrypt$210000$c2FsdA==$aGFzaA==")).isEmpty();
        assertThat(PasswordHash.parse("pbkdf2-sha256$notanumber$c2FsdA==$aGFzaA=="))
                .isEmpty();
        assertThat(PasswordHash.parse("pbkdf2-sha256$210000$not base64!$aGFzaA=="))
                .isEmpty();
        assertThat(PasswordHash.parse("pbkdf2-sha256$210000$$aGFzaA==")).isEmpty();
        assertThat(PasswordHash.parse("pbkdf2-sha256$210000$c2FsdA==$")).isEmpty();
    }

    @Test
    void toString_redactsTheSaltAndTheHash() {
        var hash = PasswordHash.of("correct-horse");
        var formatted = hash.format();
        var secondDollar = formatted.indexOf('$', formatted.indexOf('$') + 1);
        var thirdDollar = formatted.indexOf('$', secondDollar + 1);
        var salt = formatted.substring(secondDollar + 1, thirdDollar);
        var digest = formatted.substring(thirdDollar + 1);

        var text = hash.toString();

        assertThat(text).contains("pbkdf2-sha256").contains("210000");
        assertThat(text).doesNotContain(salt).doesNotContain(digest);
    }

    /**
     * Fixed output of {@code PasswordHash.of("pinned").format()}; a hash written by an older run
     * has to stay verifiable, so this string is the on-disk format and must never be regenerated.
     */
    private static final String PINNED_ENCODING =
            "pbkdf2-sha256$210000$BLJpzweGy0jZ4WaX/4QmIQ==$28GVXiO3pjmx60VND7xjChOJMvmv7UZbMfjYO29tu30=";

    @Test
    void parse_verifiesAPinnedEncoding() {
        assertThat(PasswordHash.parse(PINNED_ENCODING).orElseThrow().matches("pinned"))
                .isTrue();
    }
}
