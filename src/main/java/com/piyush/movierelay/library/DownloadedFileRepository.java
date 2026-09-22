package com.piyush.movierelay.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DownloadedFileRepository extends JpaRepository<DownloadedFile, Long> {

    /**
     * Looks up by TdApi.File.id. Only safe to use for a lookup that originated in the CURRENT
     * TDLib client session (e.g. matching an UpdateFile event to its record) - this integer is
     * not stable across app restarts, so never use it as a durable cross-session key. Use our
     * own DownloadedFile.id (JpaRepository.findById) or findByRemoteUniqueId for that.
     */
    Optional<DownloadedFile> findByTdlibFileId(int tdlibFileId);

    Optional<DownloadedFile> findByRemoteUniqueId(String remoteUniqueId);

    Optional<DownloadedFile> findByDisplayNameAndSizeBytesAndCompletedTrue(String displayName, long sizeBytes);

    List<DownloadedFile> findByLocalPath(String localPath);

    List<DownloadedFile> findByDisplayNameAndSizeBytes(String displayName, long sizeBytes);
}
