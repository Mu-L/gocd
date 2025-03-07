/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.domain;

import com.thoughtworks.go.util.ArtifactLogUtil;
import com.thoughtworks.go.work.GoPublisher;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

import static org.apache.commons.io.FileUtils.deleteQuietly;

public class ChecksumFileHandler implements FetchHandler {

    private final File checksumFile;
    private static final Logger LOG = LoggerFactory.getLogger(ChecksumFileHandler.class);

    public ChecksumFileHandler(File destination) {
        checksumFile = destination;
    }

    @Override
    public String url(String remoteHost, String workingUrl) {
        return String.format("%s/remoting/files/%s/%s/%s", remoteHost, workingUrl, ArtifactLogUtil.CRUISE_OUTPUT_FOLDER, ArtifactLogUtil.MD5_CHECKSUM_FILENAME);
    }

    @Override
    public void handle(InputStream stream) throws IOException {
        FileUtils.copyInputStreamToFile(stream, checksumFile);
    }

    @Override
    public boolean handleResult(int returncode, GoPublisher goPublisher) {
        if (returncode == HttpURLConnection.HTTP_NOT_FOUND) {
            deleteQuietly(checksumFile);
            goPublisher.taggedConsumeLineWithPrefix(GoPublisher.ERR, "[WARN] The md5checksum property file was not found on the server. Hence, Go can not verify the integrity of the artifacts.");
            return true;
        }
        if (returncode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            LOG.info("[Agent Fetch Artifact] Not downloading checksum file as it has not changed");
            return true;
        }
        if (returncode == HttpURLConnection.HTTP_OK) {
            LOG.info("[Agent Fetch Artifact] Saved checksum property file [{}]", checksumFile);
            return true;
        }
        return returncode < HttpURLConnection.HTTP_BAD_REQUEST;
    }

    @Override
    public void useArtifactMd5Checksums(ArtifactMd5Checksums artifactMd5Checksums) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChecksumFileHandler that)) {
            return false;
        }

        return checksumFile != null ? checksumFile.equals(that.checksumFile) : that.checksumFile == null;
    }

    @Override
    public int hashCode() {
        return checksumFile != null ? checksumFile.hashCode() : 0;
    }

    public ArtifactMd5Checksums getArtifactMd5Checksums() {
        return checksumFile.exists() ? new ArtifactMd5Checksums(checksumFile) : null;
    }

    public File getChecksumFile() {
        return checksumFile;
    }
}
