package com.piyush.movierelay.library;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
public class StorageController {

    private final DownloadedFileRepository downloadedFileRepository;
    private final String dataPath;

    public StorageController(DownloadedFileRepository downloadedFileRepository,
                              @Value("${tdlib.data-path}") String dataPath) {
        this.downloadedFileRepository = downloadedFileRepository;
        this.dataPath = dataPath;
    }

    @GetMapping("/api/storage")
    public StorageInfo getStorage() {
        List<DownloadedFile> all = downloadedFileRepository.findAll();

        // Sum sizeBytes for completed files only, deduped by the underlying physical file (the
        // duplicate-download cache can leave several records pointing at the same file on disk,
        // and each should only count once toward space actually used).
        Set<String> countedFiles = new LinkedHashSet<>();
        long libraryUsedBytes = 0;
        for (DownloadedFile record : all) {
            if (!record.isCompleted()) continue;
            String key = record.getDisplayName() + " " + record.getSizeBytes();
            if (countedFiles.add(key)) {
                libraryUsedBytes += record.getSizeBytes();
            }
        }

        long diskFreeBytes = 0;
        long diskTotalBytes = 0;
        try {
            File downloadsDir = Path.of(dataPath).toFile();
            downloadsDir.mkdirs();
            var fileStore = Files.getFileStore(downloadsDir.toPath());
            diskFreeBytes = fileStore.getUsableSpace();
            diskTotalBytes = fileStore.getTotalSpace();
        } catch (Exception ignored) {
            // If disk stats can't be read for some reason, just report 0 rather than failing
            // the whole endpoint - the library usage figure is still useful on its own.
        }

        return new StorageInfo(libraryUsedBytes, diskFreeBytes, diskTotalBytes);
    }
}
