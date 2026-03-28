package com.asecon.enterpriseiq.dto;

import java.util.List;

public record AssistantChatRequestDto(
    List<AssistantMessageDto> messages
) {}

