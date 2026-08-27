package com.cc.springai.agent;

import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.Map;

public record TokenUsage(Integer promptTokens,
                         Integer completionTokens,
                         Integer totalTokens) {

    public static TokenUsage from(Usage usage) {
        if (usage == null) {
            return null;
        }
        Object nativeUsage = usage.getNativeUsage();
        Integer standardPromptTokens = usage.getPromptTokens();
        Integer standardCompletionTokens = usage.getCompletionTokens();
        Integer standardTotalTokens = usage.getTotalTokens();
        boolean emptyStandardUsage = isZero(standardPromptTokens)
                && isZero(standardCompletionTokens)
                && isZero(standardTotalTokens);

        Integer nativePromptTokens = extractInt(nativeUsage, "promptTokens", "prompt_tokens", "inputTokens",
                "input_tokens");
        Integer nativeCompletionTokens = extractInt(nativeUsage, "completionTokens", "completion_tokens",
                "outputTokens", "output_tokens");
        Integer nativeTotalTokens = extractInt(nativeUsage, "totalTokens", "total_tokens");

        Integer promptTokens = chooseTokenValue(standardPromptTokens, nativePromptTokens, emptyStandardUsage);
        Integer completionTokens = chooseTokenValue(standardCompletionTokens, nativeCompletionTokens,
                emptyStandardUsage);
        Integer totalTokens = chooseTokenValue(standardTotalTokens, nativeTotalTokens, emptyStandardUsage);
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private static Integer chooseTokenValue(Integer standardValue, Integer nativeValue, boolean emptyStandardUsage) {
        if (nativeValue != null && (emptyStandardUsage || standardValue == null)) {
            return nativeValue;
        }
        if (emptyStandardUsage && isZero(standardValue)) {
            return null;
        }
        return standardValue;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                add(promptTokens, other.promptTokens),
                add(completionTokens, other.completionTokens),
                add(totalTokens, other.totalTokens));
    }

    private static Integer add(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private static Integer extractInt(Object source, String... names) {
        if (source == null) {
            return null;
        }
        if (source instanceof Map<?, ?> map) {
            for (String name : names) {
                Object value = map.get(name);
                Integer number = toInteger(value);
                if (number != null) {
                    return number;
                }
            }
            for (Object value : map.values()) {
                Integer nested = extractInt(value, names);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        for (String name : names) {
            Integer number = invokeInt(source, name);
            if (number != null) {
                return number;
            }
            number = invokeInt(source, getterName(name));
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static boolean isZero(Integer value) {
        return value != null && value == 0;
    }

    private static Integer invokeInt(Object source, String methodName) {
        try {
            Method method = source.getClass().getMethod(methodName);
            return toInteger(method.invoke(source));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getterName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
