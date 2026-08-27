package com.cc.springai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class CalculationTools {

    private static final MathContext DIVISION_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);
    private static final int MAX_EXPRESSION_LENGTH = 2_000;
    private static final int MAX_NUMBER_LENGTH = 100_000;
    private static final int MAX_RESULT_LENGTH = 100_000;
    private static final int MAX_POWER = 10_000;
    private static final int MAX_DIVISION_SCALE = 10_000;

    @Tool(description = "计算算术表达式的结果。支持整数、小数、科学计数法、括号以及 +、-、*、/、%、^ 运算符；除法结果最多保留 34 位有效数字。")
    public String calculateExpression(
            @ToolParam(description = "需要计算的表达式，例如 (12.5 + 7.5) * 3 或 2 ^ 10") String expression) {
        if (expression == null || expression.isBlank()) {
            return "计算失败：表达式不能为空。";
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return "计算失败：表达式过长。";
        }

        try {
            return formatResult(new ExpressionParser(expression).parse());
        } catch (IllegalArgumentException | ArithmeticException e) {
            return "计算失败：" + e.getMessage();
        }
    }

    @Tool(description = "执行任意精度大数计算。operation 支持 add、subtract、multiply、divide、remainder 和 power；除法不能整除时请提供 scale 指定小数位数。")
    public String calculateBigNumber(
            @ToolParam(description = "操作类型：add、subtract、multiply、divide、remainder 或 power") String operation,
            @ToolParam(description = "左操作数，可为超长整数或高精度小数") String leftOperand,
            @ToolParam(description = "右操作数；power 操作时必须为整数指数") String rightOperand,
            @ToolParam(description = "divide 操作的可选小数位数，使用 HALF_UP 舍入", required = false) Integer scale) {
        try {
            String normalizedOperation = operation == null ? "" : operation.strip().toLowerCase(Locale.ROOT);
            BigDecimal left = parseNumber(leftOperand);
            BigDecimal right = parseNumber(rightOperand);
            BigDecimal result = switch (normalizedOperation) {
                case "add", "+" -> left.add(right);
                case "subtract", "-" -> left.subtract(right);
                case "multiply", "*" -> left.multiply(right);
                case "divide", "/" -> divide(left, right, scale);
                case "remainder", "%" -> left.remainder(right);
                case "power", "pow", "^" -> power(left, right);
                default -> throw new IllegalArgumentException("不支持的操作类型：" + operation);
            };
            return formatResult(result);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return "大数计算失败：" + e.getMessage();
        }
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, Integer scale) {
        if (right.signum() == 0) {
            throw new ArithmeticException("除数不能为零。");
        }
        if (scale == null) {
            try {
                return left.divide(right);
            } catch (ArithmeticException e) {
                throw new ArithmeticException("除法结果为无限小数，请提供 scale 指定保留的小数位数。");
            }
        }
        if (scale < 0 || scale > MAX_DIVISION_SCALE) {
            throw new IllegalArgumentException("scale 必须在 0 到 " + MAX_DIVISION_SCALE + " 之间。");
        }
        return left.divide(right, scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        int integerExponent;
        try {
            integerExponent = exponent.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("指数必须是整数。");
        }
        if (integerExponent < 0 || integerExponent > MAX_POWER) {
            throw new IllegalArgumentException("大数计算的指数必须在 0 到 " + MAX_POWER + " 之间。");
        }
        ensurePowerResultIsBounded(base, integerExponent);
        return base.pow(integerExponent);
    }

    private static BigDecimal parseNumber(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("操作数不能为空。");
        }
        String normalized = text.strip();
        if (normalized.length() > MAX_NUMBER_LENGTH) {
            throw new IllegalArgumentException("操作数过长。");
        }
        try {
            BigDecimal number = new BigDecimal(normalized);
            ensureResultIsBounded(number);
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("操作数不是有效数字：" + text);
        }
    }

    private static String formatResult(BigDecimal result) {
        BigDecimal normalized = result.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        ensureResultIsBounded(normalized);
        return normalized.toPlainString();
    }

    private static void ensurePowerResultIsBounded(BigDecimal base, int exponent) {
        long estimatedDigits = (long) Math.max(1, base.stripTrailingZeros().precision()) * exponent;
        if (estimatedDigits > MAX_RESULT_LENGTH) {
            throw new IllegalArgumentException("计算结果过长。");
        }
    }

    private static void ensureResultIsBounded(BigDecimal result) {
        long integerDigits = Math.max(1L, (long) result.precision() - result.scale());
        long fractionalDigits = Math.max(0L, result.scale());
        if (integerDigits + fractionalDigits + 2 > MAX_RESULT_LENGTH) {
            throw new IllegalArgumentException("计算结果过长。");
        }
    }

    private static final class ExpressionParser {
        private final String expression;
        private int position;

        private ExpressionParser(String expression) {
            this.expression = expression;
        }

        private BigDecimal parse() {
            BigDecimal value = parseAdditive();
            skipWhitespace();
            if (position != expression.length()) {
                throw error("无法识别的字符 '" + expression.charAt(position) + "'");
            }
            return value;
        }

        private BigDecimal parseAdditive() {
            BigDecimal value = parseMultiplicative();
            while (true) {
                if (match('+')) {
                    value = value.add(parseMultiplicative());
                } else if (match('-')) {
                    value = value.subtract(parseMultiplicative());
                } else {
                    return value;
                }
                ensureResultIsBounded(value);
            }
        }

        private BigDecimal parseMultiplicative() {
            BigDecimal value = parseUnary();
            while (true) {
                if (match('*')) {
                    value = value.multiply(parseUnary());
                } else if (match('/')) {
                    BigDecimal divisor = parseUnary();
                    if (divisor.signum() == 0) {
                        throw new ArithmeticException("除数不能为零。");
                    }
                    value = value.divide(divisor, DIVISION_CONTEXT);
                } else if (match('%')) {
                    BigDecimal divisor = parseUnary();
                    if (divisor.signum() == 0) {
                        throw new ArithmeticException("除数不能为零。");
                    }
                    value = value.remainder(divisor);
                } else {
                    return value;
                }
                ensureResultIsBounded(value);
            }
        }

        private BigDecimal parseUnary() {
            if (match('+')) {
                return parseUnary();
            }
            if (match('-')) {
                return parseUnary().negate();
            }
            return parsePower();
        }

        private BigDecimal parsePower() {
            BigDecimal base = parsePrimary();
            if (!match('^')) {
                return base;
            }
            BigDecimal exponent = parseUnary();
            int integerExponent;
            try {
                integerExponent = exponent.intValueExact();
            } catch (ArithmeticException e) {
                throw error("指数必须是整数");
            }
            if (Math.abs((long) integerExponent) > MAX_POWER) {
                throw error("指数绝对值不能超过 " + MAX_POWER);
            }
            if (integerExponent >= 0) {
                ensurePowerResultIsBounded(base, integerExponent);
                return base.pow(integerExponent);
            }
            if (base.signum() == 0) {
                throw new ArithmeticException("零不能取负指数。");
            }
            ensurePowerResultIsBounded(base, -integerExponent);
            return BigDecimal.ONE.divide(base.pow(-integerExponent), DIVISION_CONTEXT);
        }

        private BigDecimal parsePrimary() {
            if (match('(')) {
                BigDecimal value = parseAdditive();
                if (!match(')')) {
                    throw error("缺少右括号");
                }
                return value;
            }
            return parseLiteral();
        }

        private BigDecimal parseLiteral() {
            skipWhitespace();
            int start = position;
            boolean hasDigits = consumeDigits();
            if (consume('.')) {
                hasDigits = consumeDigits() || hasDigits;
            }
            if (!hasDigits) {
                throw error("需要数字或左括号");
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                if (!consumeDigits()) {
                    throw error("科学计数法指数无效");
                }
            }
            return parseNumber(expression.substring(start, position));
        }

        private boolean consumeDigits() {
            int start = position;
            while (position < expression.length() && Character.isDigit(expression.charAt(position))) {
                position++;
            }
            return position > start;
        }

        private boolean match(char expected) {
            skipWhitespace();
            return consume(expected);
        }

        private boolean consume(char expected) {
            if (position < expression.length() && expression.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
                position++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + position + "）。");
        }
    }
}
