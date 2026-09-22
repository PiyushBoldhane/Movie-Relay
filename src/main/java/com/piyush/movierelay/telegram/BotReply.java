package com.piyush.movierelay.telegram;

import java.util.List;

public record BotReply(long messageId, String text, List<List<BotReplyButton>> buttonRows, BotFile file,
                        String posterUrl) {
}
