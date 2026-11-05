package ca.yorku.eecs3311.detourto.infrastructure;

import java.io.IOException;

/** Test helper: exposes the package-private CKAN parsing to tests in other packages. */
public final class OpenDataDownloaderAccess {

    private OpenDataDownloaderAccess() {
    }

    public static String zipFromCkan(String json) throws IOException {
        return OpenDataDownloader.zipFromCkan(json);
    }
}
