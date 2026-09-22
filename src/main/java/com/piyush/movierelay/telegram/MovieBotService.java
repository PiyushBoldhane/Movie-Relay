package com.piyush.movierelay.telegram;

import com.piyush.movierelay.poster.TitleCleaner;
import com.piyush.movierelay.poster.TmdbService;
import com.piyush.movierelay.web.ApiException;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MovieBotService {

    private static final Logger log = LoggerFactory.getLogger(MovieBotService.class);
    private static final Pattern CONFIRMED_TITLE = Pattern.compile("Title\\s*:\\s*(.+)");

    private final TelegramClientService telegramClientService;
    private final TelegramProperties telegramProperties;
    private final DownloadService downloadService;
    private final TmdbService tmdbService;

    private volatile long botChatId = 0;
    private volatile long botUserId = 0;
    private final AtomicReference<CompletableFuture<TdApi.Message>> pendingReply = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Long>> pendingEdit = new AtomicReference<>();
    private volatile long pendingEditMessageId = 0;

    // Only one search/click "conversation turn" with the bot can be in flight at a time -
    // both operations share the pendingReply/pendingEdit slots above, so a second concurrent
    // call would silently steal or corrupt the first call's response. Fail fast instead of
    // queuing, since a caller waiting behind an unrelated 30s timeout is a worse experience
    // than an immediate "try again" error.
    private final Semaphore conversationLock = new Semaphore(1);

    public MovieBotService(TelegramClientService telegramClientService, TelegramProperties telegramProperties,
                            DownloadService downloadService, TmdbService tmdbService) {
        this.telegramClientService = telegramClientService;
        this.telegramProperties = telegramProperties;
        this.downloadService = downloadService;
        this.tmdbService = tmdbService;
    }

    private SimpleTelegramClient client() {
        return telegramClientService.getClient();
    }

    private long resolveBotChatId() {
        if (botChatId != 0) {
            return botChatId;
        }
        try {
            TdApi.SearchPublicChat request = new TdApi.SearchPublicChat(telegramProperties.getMovieBotUsername());
            TdApi.Chat chat = client().send(request).get(30, TimeUnit.SECONDS);
            botChatId = chat.id;

            if (chat.type instanceof TdApi.ChatTypePrivate privateChat) {
                botUserId = privateChat.userId;
            } else {
                throw new ApiException("bot_config_invalid", HttpStatus.INTERNAL_SERVER_ERROR,
                        "'" + telegramProperties.getMovieBotUsername() + "' did not resolve to a private bot chat. Check the configured bot username.");
            }

            client().addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);
            client().addUpdateHandler(TdApi.UpdateMessageEdited.class, this::onMessageEdited);

            log.info("Resolved movie bot '{}' to chat id {}", telegramProperties.getMovieBotUsername(), botChatId);
            return botChatId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("bot_unreachable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while connecting to the movie bot", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ApiException("bot_unreachable", HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reach the movie bot '" + telegramProperties.getMovieBotUsername() + "'", e);
        }
    }

    private void onNewMessage(TdApi.UpdateNewMessage update) {
        TdApi.Message message = update.message;
        if (message.chatId != botChatId) {
            return;
        }
        // Only react to messages sent by the bot itself, not our own outgoing message.
        if (!(message.senderId instanceof TdApi.MessageSenderUser senderUser) || senderUser.userId != botUserId) {
            return;
        }
        CompletableFuture<TdApi.Message> future = pendingReply.getAndSet(null);
        if (future != null) {
            future.complete(message);
        }
    }

    private void onMessageEdited(TdApi.UpdateMessageEdited update) {
        if (update.chatId != botChatId) {
            return;
        }
        CompletableFuture<Long> future = pendingEdit.getAndSet(null);
        if (future != null && pendingEditMessageId == update.messageId) {
            future.complete(update.messageId);
        }
    }

    public BotReply search(String query) {
        if (!conversationLock.tryAcquire()) {
            throw new ApiException("bot_busy", HttpStatus.CONFLICT,
                    "Already waiting on a response from the movie bot. Please try again shortly.");
        }
        try {
            return doSearch(query);
        } finally {
            conversationLock.release();
        }
    }

    private BotReply doSearch(String query) {
        long chatId = resolveBotChatId();

        CompletableFuture<TdApi.Message> future = new CompletableFuture<>();
        pendingReply.set(future);

        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(query, null);

        TdApi.SendMessage sendMessage = new TdApi.SendMessage();
        sendMessage.chatId = chatId;
        sendMessage.inputMessageContent = content;

        try {
            client().send(sendMessage).get(10, TimeUnit.SECONDS);

            TdApi.Message reply = future.get(30, TimeUnit.SECONDS);
            return toBotReply(reply);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("bot_timeout", HttpStatus.GATEWAY_TIMEOUT,
                    "Interrupted while waiting for the movie bot to reply", e);
        } catch (ExecutionException | TimeoutException e) {
            pendingReply.set(null);
            throw new ApiException("bot_timeout", HttpStatus.GATEWAY_TIMEOUT,
                    "The movie bot did not reply in time. Please try searching again.", e);
        }
    }

    public BotReply clickButton(long messageId, byte[] callbackData) {
        if (!conversationLock.tryAcquire()) {
            throw new ApiException("bot_busy", HttpStatus.CONFLICT,
                    "Already waiting on a response from the movie bot. Please try again shortly.");
        }
        try {
            return doClickButton(messageId, callbackData);
        } finally {
            conversationLock.release();
        }
    }

    private BotReply doClickButton(long messageId, byte[] callbackData) {
        long chatId = resolveBotChatId();

        CompletableFuture<Long> editFuture = new CompletableFuture<>();
        pendingEditMessageId = messageId;
        pendingEdit.set(editFuture);

        CompletableFuture<TdApi.Message> newMessageFuture = new CompletableFuture<>();
        pendingReply.set(newMessageFuture);

        TdApi.GetCallbackQueryAnswer request = new TdApi.GetCallbackQueryAnswer();
        request.chatId = chatId;
        request.messageId = messageId;
        request.payload = new TdApi.CallbackQueryPayloadData(callbackData);

        try {
            TdApi.CallbackQueryAnswer answer = client().send(request).get(15, TimeUnit.SECONDS);

            String startParameter = extractStartParameter(answer.url);
            if (startParameter != null) {
                // This button is a deep-link (e.g. a file selection), not an in-place edit.
                // We don't need to wait on the edit path at all - only the new file message.
                pendingEdit.set(null);

                TdApi.SendBotStartMessage startMessage = new TdApi.SendBotStartMessage(botUserId, chatId, startParameter);
                client().send(startMessage).get(15, TimeUnit.SECONDS);

                TdApi.Message fileMessage = newMessageFuture.get(30, TimeUnit.SECONDS);
                pendingReply.set(null);
                return toBotReply(fileMessage);
            }

            CompletableFuture<Object> either = editFuture.thenApply(id -> (Object) id)
                    .applyToEither(newMessageFuture.thenApply(msg -> (Object) msg), x -> x);

            Object result = either.get(20, TimeUnit.SECONDS);
            pendingEdit.set(null);
            pendingReply.set(null);

            if (result instanceof TdApi.Message newMessage) {
                return toBotReply(newMessage);
            }

            TdApi.GetMessage getMessage = new TdApi.GetMessage(chatId, messageId);
            TdApi.Message updatedMessage = client().send(getMessage).get(15, TimeUnit.SECONDS);
            return toBotReply(updatedMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("bot_timeout", HttpStatus.GATEWAY_TIMEOUT,
                    "Interrupted while waiting for the movie bot's response", e);
        } catch (ExecutionException | TimeoutException e) {
            pendingEdit.set(null);
            pendingReply.set(null);

            if (isStaleCallback(e)) {
                throw new ApiException("stale_selection", HttpStatus.GONE,
                        "This file selection has expired. Please search again to refresh the results.", e);
            }
            throw new ApiException("bot_timeout", HttpStatus.GATEWAY_TIMEOUT,
                    "The movie bot did not respond to that selection in time. Please try again.", e);
        }
    }

    // Telegram answers a callback query with 400 DATA_INVALID when the button's callback data
    // (or the message it belongs to) is no longer valid - e.g. the inline keyboard was replaced
    // or the query is too old. This is not a transient timeout and retrying the same click will
    // never succeed; the user needs a fresh search to get new, valid buttons.
    private static boolean isStaleCallback(Throwable e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        return cause instanceof TelegramError telegramError
                && "DATA_INVALID".equals(telegramError.getErrorMessage());
    }

    /**
     * Telegram file-sharing bots often answer a callback query with a t.me deep link
     * (e.g. "https://t.me/BotUsername?start=file_XYZ") instead of editing the message or
     * sending a reply directly. The client is expected to follow that link, which for a bot
     * means sending a /start command with the given payload. This extracts that payload.
     */
    private String extractStartParameter(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String query = URI.create(url).getQuery();
            if (query == null) {
                return null;
            }
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq > 0 && param.substring(0, eq).equals("start")) {
                    return param.substring(eq + 1);
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BotReply toBotReply(TdApi.Message message) {
        String text = "";
        DetectedFile detected = null;

        if (message.content instanceof TdApi.MessageText messageText) {
            text = messageText.text.text;
        } else if (message.content instanceof TdApi.MessageDocument messageDocument) {
            text = messageDocument.caption != null ? messageDocument.caption.text : "";
            TdApi.Document doc = messageDocument.document;
            detected = new DetectedFile(doc.document.id, doc.document.remote.uniqueId, doc.document.remote.id,
                    doc.fileName, doc.mimeType, doc.document.size);
        } else if (message.content instanceof TdApi.MessageVideo messageVideo) {
            text = messageVideo.caption != null ? messageVideo.caption.text : "";
            TdApi.Video video = messageVideo.video;
            detected = new DetectedFile(video.video.id, video.video.remote.uniqueId, video.video.remote.id,
                    video.fileName, video.mimeType, video.video.size);
        }

        BotFile file = null;
        if (detected != null) {
            // The bot deletes these file messages ~60 seconds after sending them, so we must
            // start pulling the file into our own storage immediately, not wait for a later
            // user action like "press play".
            log.info("Detected file '{}' ({} bytes) - starting download immediately", detected.fileName(), detected.sizeBytes());
            long libraryId = downloadService.startDownload(detected);
            file = new BotFile(libraryId, detected.fileName(), detected.mimeType(), detected.sizeBytes());
        }

        List<List<BotReplyButton>> buttonRows = new ArrayList<>();
        if (message.replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard inlineKeyboard) {
            for (TdApi.InlineKeyboardButton[] row : inlineKeyboard.rows) {
                List<BotReplyButton> rowButtons = new ArrayList<>();
                for (TdApi.InlineKeyboardButton button : row) {
                    byte[] callbackData = null;
                    if (button.type instanceof TdApi.InlineKeyboardButtonTypeCallback callback) {
                        callbackData = callback.data;
                    }
                    rowButtons.add(new BotReplyButton(button.text, callbackData, LabelParser.parse(button.text)));
                }
                buttonRows.add(rowButtons);
            }
        }

        String posterUrl = findPosterForReply(text);

        return new BotReply(message.id, text, buttonRows, file, posterUrl);
    }

    // The bot confirms which title it matched with a line like "Title : Money Heist" - reuse
    // that same confirmed title (rather than the raw search query, which may be a fuzzy/partial
    // match) to look up one poster for the whole results screen. Mirrors the "Title :" pattern
    // the frontend already parses out of this text for its own display purposes.
    private String findPosterForReply(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = CONFIRMED_TITLE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String confirmedTitle = matcher.group(1).trim();
        TitleCleaner.CleanedTitle cleaned = TitleCleaner.clean(confirmedTitle);
        String title = cleaned.title().isBlank() ? confirmedTitle : cleaned.title();
        return tmdbService.findPosterUrl(title, cleaned.year()).orElse(null);
    }
}
