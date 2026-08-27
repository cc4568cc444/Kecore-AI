package com.cc.springai.agent;

import java.util.List;

public record InteractionRequest(String runId,
                                 String type,
                                 String question,
                                 List<String> options,
                                 String placeholder,
                                 String description,
                                 boolean allowCustomInput) {
}
