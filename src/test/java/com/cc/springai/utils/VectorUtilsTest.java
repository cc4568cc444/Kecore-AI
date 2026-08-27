package com.cc.springai.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorUtilsTest {

    @Test
    void calculatesEuclideanDistance() {
        double distance = VectorUtils.euclideanDistance(
                new float[]{1.0F, 2.0F},
                new float[]{4.0F, 6.0F});

        assertThat(distance).isEqualTo(5.0);
    }

    @Test
    void returnsZeroForEqualVectors() {
        assertThat(VectorUtils.euclideanDistance(
                new float[]{0.25F, -0.5F},
                new float[]{0.25F, -0.5F}))
                .isZero();
    }

    @Test
    void rejectsVectorsWithDifferentDimensions() {
        assertThatThrownBy(() -> VectorUtils.euclideanDistance(
                new float[]{1.0F},
                new float[]{1.0F, 2.0F}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vectors must have the same dimensions.");
    }

    @Test
    void rejectsNullVectors() {
        assertThatThrownBy(() -> VectorUtils.euclideanDistance(null, new float[]{1.0F}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vectors must not be null.");
    }
}
