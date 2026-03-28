package com.asecon.enterpriseiq.dto;

import java.util.List;

public record AssistantChatResponseDto(
    String reply,
    List<String> questions,
    List<AdvisorActionDto> actions,
    List<String> suggestedPrompts
) {}

