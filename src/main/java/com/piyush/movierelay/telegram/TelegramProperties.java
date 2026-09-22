package com.piyush.movierelay.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    private int apiId;
    private String apiHash;
    private String movieBotUsername;

    public int getApiId() {
        return apiId;
    }

    public void setApiId(int apiId) {
        this.apiId = apiId;
    }

    public String getApiHash() {
        return apiHash;
    }

    public void setApiHash(String apiHash) {
        this.apiHash = apiHash;
    }

    public String getMovieBotUsername() {
        return movieBotUsername;
    }

    public void setMovieBotUsername(String movieBotUsername) {
        this.movieBotUsername = movieBotUsername;
    }
}
