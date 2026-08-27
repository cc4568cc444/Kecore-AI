package com.cc.springai.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculationToolsTest {

    private final CalculationTools tools = new CalculationTools();

    @Test
    void evaluatesArithmeticExpressionsWithPrecedenceAndPowers() {
        assertThat(tools.calculateExpression("(12.5 + 7.5) * 3 - 2 ^ 3"))
                .isEqualTo("52");
        assertThat(tools.calculateExpression("2 ^ -3"))
                .isEqualTo("0.125");
    }

    @Test
    void evaluatesDivisionWithUsefulPrecision() {
        assertThat(tools.calculateExpression("1 / 3"))
                .isEqualTo("0.3333333333333333333333333333333333");
    }

    @Test
    void calculatesArbitraryPrecisionNumbersExactly() {
        assertThat(tools.calculateBigNumber(
                "multiply", "12378901237890", "98765432109876543210", null))
                .isEqualTo("1219326311370217952237463801111263526900");
        assertThat(tools.calculateBigNumber("divide", "1", "3", 10))
                .isEqualTo("0.3333333333");
    }

    @Test
    void returnsReadableErrorsForInvalidCalculations() {
        assertThat(tools.calculateExpression("1 / 0"))
                .contains("计算失败").contains("除数不能为零");
        assertThat(tools.calculateBigNumber("divide", "1", "3", null))
                .contains("大数计算失败").contains("scale");
    }
}
