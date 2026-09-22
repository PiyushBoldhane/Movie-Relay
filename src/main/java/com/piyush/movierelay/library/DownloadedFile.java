package com.piyush.movierelay.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A downloaded (or downloading) file. Our own auto-generated {@link #id} is the stable,
 * permanent identifier used everywhere outside this package (URLs, the frontend, other
 * services) - NOT tdlibFileId.
 * <p>
 * TdApi.File.id ("tdlibFileId" here) is only valid for the lifetime of the TDLib client
 * session that produced it: TDLib can silently reassign that integer to a completely
 * different file once a new session starts (e.g. after this app restarts), so it must never
 * be treated as a permanent key. remoteUniqueId (from TdApi.RemoteFile.uniqueId) is the
 * value TDLib itself guarantees is stable for the same file content over time, and is what we
 * use to detect "this is the same file" (e.g. for the duplicate-download cache). remoteFileId
 * (TdApi.RemoteFile.id) is the resolvable handle passed to GetRemoteFile to mint a fresh,
 * valid tdlibFileId for the current session when the cached one can no longer be trusted.
 */
@Entity
public class DownloadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int tdlibFileId;

    @Column
    private String remoteUniqueId;

    @Column
    private String remoteFileId;

    @Column(nullable = false)
    private String displayName;

    private String mimeType;

    private long sizeBytes;

    @Column(nullable = false)
    private String localPath;

    @Column(nullable = false)
    private boolean completed;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean paused;

    @Column(nullable = false)
    private Instant downloadedAt;

    // How far into playback the user last got, in seconds - lets the player resume where they
    // left off instead of always starting from 0. Stored server-side (not per-browser) so
    // resuming works the same from any device.
    @Column(nullable = false)
    @ColumnDefault("0")
    private double lastPositionSeconds;

    // The ffmpeg stream index of the audio track the user picked for this file (null = play the
    // file as downloaded, with whichever audio track is its default). When set, the video is
    // served from a remuxed copy containing only the video track and this one audio track -
    // see AudioTrackService - since browsers can't switch between embedded audio tracks in an
    // MKV during playback.
    @Column
    private Integer activeAudioTrackIndex;

    protected DownloadedFile() {
        // required by JPA
    }

    public DownloadedFile(int tdlibFileId, String remoteUniqueId, String remoteFileId, String displayName,
                           String mimeType, long sizeBytes, String localPath, boolean completed, Instant downloadedAt) {
        this.tdlibFileId = tdlibFileId;
        this.remoteUniqueId = remoteUniqueId;
        this.remoteFileId = remoteFileId;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.localPath = localPath;
        this.completed = completed;
        this.downloadedAt = downloadedAt;
    }

    public Long getId() {
        return id;
    }

    public int getTdlibFileId() {
        return tdlibFileId;
    }

    public void setTdlibFileId(int tdlibFileId) {
        this.tdlibFileId = tdlibFileId;
    }

    public String getRemoteUniqueId() {
        return remoteUniqueId;
    }

    public String getRemoteFileId() {
        return remoteFileId;
    }

    public void setRemoteFileId(String remoteFileId) {
        this.remoteFileId = remoteFileId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }

    public double getLastPositionSeconds() {
        return lastPositionSeconds;
    }

    public void setLastPositionSeconds(double lastPositionSeconds) {
        this.lastPositionSeconds = lastPositionSeconds;
    }

    public Integer getActiveAudioTrackIndex() {
        return activeAudioTrackIndex;
    }

    public void setActiveAudioTrackIndex(Integer activeAudioTrackIndex) {
        this.activeAudioTrackIndex = activeAudioTrackIndex;
    }
}
