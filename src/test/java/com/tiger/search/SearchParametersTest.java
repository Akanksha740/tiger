package com.tiger.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SearchParametersTest {
    @Test
    void defaultsLimitAndOffset() {
        assertThat(SearchParameters.validateLimit(null)).isEqualTo(25);
        assertThat(SearchParameters.validateOffset(null)).isZero();
    }

    @Test
    void rejectsLimitOutsideSearchCap() {
        assertThatThrownBy(() -> SearchParameters.validateLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");

        assertThatThrownBy(() -> SearchParameters.validateLimit(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
    }

    @Test
    void rejectsNegativeOffset() {
        assertThatThrownBy(() -> SearchParameters.validateOffset(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must be zero or greater");
    }
}
