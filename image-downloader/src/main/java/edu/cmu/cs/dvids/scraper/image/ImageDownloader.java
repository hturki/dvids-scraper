/*
 * The OpenDiamond Platform for Interactive Search
 *
 * Copyright (c) 2020 Carnegie Mellon University
 * All rights reserved.
 *
 * This software is distributed under the terms of the Eclipse Public
 * License, Version 1.0 which can be found in the file named LICENSE.
 * ANY USE, REPRODUCTION OR DISTRIBUTION OF THIS SOFTWARE CONSTITUTES
 * RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package edu.cmu.cs.dvids.scraper.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Queues;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import edu.cmu.cs.dvids.scraper.image.config.ImageDownloaderConfig;
import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImageDownloader extends Application<ImageDownloaderConfig> {

    private static final Logger log = LoggerFactory.getLogger(ImageDownloader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new GuavaModule());

    private static final String ASSET_URL = "https://api.dvidshub.net/asset";
    private static final String CDN_URL = "https://cdn.dvidshub.net/media/photos";
    private static final String ID_PREFIX = "image:";

    private ImageDownloader() {
    }

    @Override
    public void run(ImageDownloaderConfig config, Environment environment) throws Exception {
        Path outputDir = Paths.get(config.outputDir());
        outputDir.toFile().mkdir();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();

        BlockingQueue<DownloadMetadata> idQueue = new ArrayBlockingQueue<>(10000);

        int threads = Runtime.getRuntime().availableProcessors();
        ListeningExecutorService downloaderService = MoreExecutors.listeningDecorator(environment.lifecycle()
                .executorService("downloader-%d")
                .minThreads(threads)
                .maxThreads(threads)
                .build());

        AtomicBoolean queuingFinished = new AtomicBoolean(false);
        List<ListenableFuture<?>> downloadThreads =
                IntStream.range(0, threads).mapToObj(i -> downloaderService.submit(() -> {
                    boolean firstAttempt = true;

                    try {
                        while (true) {
                            List<DownloadMetadata> imageIds = new ArrayList<>(10);
                            Queues.drain(idQueue, imageIds, 10, Duration.ofMillis(100));

                            if (imageIds.isEmpty() && queuingFinished.get()) {
                                if (!firstAttempt) {
                                    break;
                                } else {
                                    firstAttempt = false;
                                    continue;
                                }
                            }

                            imageIds.forEach(imageId -> downloadImage(
                                    client,
                                    imageId,
                                    outputDir,
                                    1));
                        }
                    } catch (RuntimeException | Error | InterruptedException e) {
                        log.error("Downloader {} failed", i, e);
                    }
                })).collect(Collectors.toList());

        try (CSVParser parser = CSVParser.parse(Paths.get(config.inputFile()), StandardCharsets.UTF_8,
                CSVFormat.DEFAULT)) {
            while (parser.iterator().hasNext()) {
                CSVRecord record = parser.iterator().next();
                String imageId = record.get(0);
                Preconditions.checkArgument(imageId.startsWith(ID_PREFIX), "Unexpected id %s", imageId);

                String thumbnail = record.get(16);
                if (thumbnail != null && thumbnail.endsWith(".jpg")) {
                    List<String> splits = Splitter.on("/").splitToList(thumbnail);
                    idQueue.put(new DownloadMetadata(
                            imageId.substring(ID_PREFIX.length()),
                            Integer.parseInt(record.get(10)),
                            (int) Double.parseDouble(record.get(21)),
                            String.format(
                                    "%s/%s/%s.jpg",
                                    CDN_URL,
                                    splits.get(splits.size() - 3),
                                    splits.get(splits.size() - 2))));
                } else {
                    idQueue.put(getMetadataFromAsset(client, config.apiKey(), imageId.substring(ID_PREFIX.length())));
                }
            }

            queuingFinished.set(true);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to queue ids from csv file: " + config.inputFile(), e);
        }

        Futures.allAsList(downloadThreads).get();
    }

    private DownloadMetadata getMetadataFromAsset(OkHttpClient client, String apiKey, String imageId) {
        HttpUrl url = HttpUrl.parse(ASSET_URL).newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("id", "image:" + imageId)
                .build();
        AssetResult assetResult = getAssetResult(client, url);

        String imageUrl = assetResult.image();
        if (!imageUrl.endsWith(imageId + ".jpg")) {
            throw new RuntimeException(String.format("Unexpected image url %s for image id %s",
                    imageUrl, imageId));
        }

        return new DownloadMetadata(
                imageId,
                Integer.parseInt(assetResult.dimensions().height()),
                Integer.parseInt(assetResult.dimensions().width()),
                assetResult.image());
    }

    private void downloadImage(
            OkHttpClient client,
            DownloadMetadata metadata,
            Path outputDir,
            CSVPrinter printer,
            int attempt) {
        String idHashSubstr = Hashing.sha256()
                .hashString(metadata.imageId, StandardCharsets.UTF_8)
                .toString().substring(0, 2);

        Path outputSubDir = outputDir.resolve(idHashSubstr);

        outputSubDir.toFile().mkdir();
        Path imageTmpPath = outputSubDir.resolve(metadata.imageId + ".tmp.jpg");

        try {
            Path imagePath = outputSubDir.resolve(metadata.imageId + ".jpg");
            if (imagePath.toFile().exists()) {
                log.debug("Path {} already exists - skipping", imagePath);
                return;
            }

            getUrl(client, HttpUrl.parse(metadata.url), r -> {
                try {
                    if (imageTmpPath.toFile().exists()) {
                        imageTmpPath.toFile().delete();
                    }

                    return Files.copy(r.body().source().inputStream(), imageTmpPath);
                } catch (IOException e) {
                    throw new RuntimeException(String.format("Failed to save image %s to path %s and url %s",
                            metadata.imageId, imageTmpPath, metadata.url), e);
                }
            });

            Optional<BufferedImage> image = readImage(imageTmpPath);
            if (image.isPresent()) {
                int width = image.get().getWidth();
                int height = image.get().getHeight();

                if (width != metadata.width || height != metadata.height) {
                    log.warn("Downloaded image dimensions differ from published metadata (expected: {}x{}, got: {}x{}",
                            metadata.height, metadata.width, height, width);
                }

                Preconditions.checkArgument(width == metadata.width, "Unexpected image width (expected: %s, got: %s)",
                        metadata.width, width);

                Preconditions.checkArgument(
                        height == metadata.height,
                        "Unexpected image height (expected: %s, got: %s)",
                        metadata.height, height);

                Files.move(imageTmpPath, imagePath, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (RuntimeException | IOException | Error e) {
            if (attempt < 3) {
                log.error("Failed to download image {} to tmp path {} and url {}. Retrying...",
                        metadata.imageId, imageTmpPath, metadata.url, e);
                downloadImage(client, metadata, outputDir, attempt + 1);
            } else {
                throw new RuntimeException(String.format("Failed to download image %s to tmp path %s and url %s",
                        metadata.imageId, imageTmpPath, metadata.url), e);
            }
        }
    }

    private Optional<BufferedImage> readImage(Path image) {
        try {
            return Optional.of(ImageIO.read(image.toFile()));
        } catch (IOException e) {
            log.error("Failed to read image {}", image, e);
            return Optional.empty();
        }
    }

    private AssetResult getAssetResult(OkHttpClient client, HttpUrl url) {
        return getUrl(client, url, this::parseAssetResult);
    }

    private <T> T getUrl(OkHttpClient client, HttpUrl url, Function<Response, T> responseFn) {
        return getUrl(client, url, responseFn, 1);
    }

    private <T> T getUrl(OkHttpClient client, HttpUrl url, Function<Response, T> responseFn, int attempt) {
        Request request = new Request.Builder().url(url).build();
        log.info("Requesting url {}", url);
        try (Response response = client.newCall(request).execute()) {
            return responseFn.apply(response);
        } catch (IOException | RuntimeException e) {
            if (attempt < 3) {
                log.error("Failed to execute request to url: {}. Retrying...", url, e);
                return getUrl(client, url, responseFn, attempt + 1);
            } else {
                throw new RuntimeException("Failed to execute request to url: " + url, e);
            }
        }
    }

    private AssetResult parseAssetResult(Response response) {
        String responseString = getResponseString(response);

        try {
            return MAPPER.readValue(responseString, AssetResponse.class).results();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse response: " + responseString, e);
        }
    }

    @NotNull
    private String getResponseString(Response response) {
        try {
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get response body string", e);
        }
    }

    public static void main(String[] args) throws Exception {
        new ImageDownloader().run(args);
    }

    private static final class DownloadMetadata {
        private final String imageId;
        private final int height;
        private final int width;
        private final String url;

        private DownloadMetadata(String imageId, int height, int width, String url) {
            this.imageId = imageId;
            this.height = height;
            this.width = width;
            this.url = url;
        }
    }
}
