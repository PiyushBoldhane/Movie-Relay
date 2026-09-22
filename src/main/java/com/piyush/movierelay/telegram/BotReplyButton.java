package com.piyush.movierelay.telegram;

public record BotReplyButton(String text, byte[] callbackData, ParsedLabel parsed) {

    public record ParsedLabel(Integer season, Integer episode, String resolution, String language, String sizeText) {
    }
}
