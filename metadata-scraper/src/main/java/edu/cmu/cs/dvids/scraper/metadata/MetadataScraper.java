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

package edu.cmu.cs.dvids.scraper.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import edu.cmu.cs.dvids.scraper.metadata.config.MetadataScraperConfig;
import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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

public final class MetadataScraper extends Application<MetadataScraperConfig> {

    private static final Logger log = LoggerFactory.getLogger(MetadataScraper.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new GuavaModule());

    private static final String SEARCH_URL = "https://api.dvidshub.net/search";
    private static final String ID_PREFIX = "image:";

    private MetadataScraper() {
    }

    @Override
    public void run(MetadataScraperConfig config, Environment _environment) {
        Path outputDir = Paths.get(config.outputDir());
        outputDir.toFile().mkdir();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .build();

        LocalDate date = config.latestDate();
        while (date.isAfter(LocalDate.of(2013, 12, 31))) {
            saveDate(client, config.apiKey(), date, outputDir);
            date = date.minusDays(1);
        }

        mergeAll(outputDir, config.numSplits());
    }

    private void mergeAll(Path outputDir, int numSplits) {
        Set<Integer> visited = new HashSet<>();
        Map<Integer, CSVPrinter> printers = new HashMap<>();
        try (Stream<Path> outputFiles = Files.list(outputDir).filter(f -> f.toString().endsWith(".csv"))) {
            outputFiles.forEach(csvFile -> {
                log.info("Merging file {}", csvFile);

                try (CSVParser parser = CSVParser.parse(csvFile, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
                    while (parser.iterator().hasNext()) {
                        CSVRecord record = parser.iterator().next();
                        String imageId = record.get(0);
                        Preconditions.checkArgument(imageId.startsWith(ID_PREFIX), "Unexpected id %s", imageId);
                        final int imageIdInt = Integer.parseInt(imageId.substring(ID_PREFIX.length()));
                        if (visited.contains(imageIdInt)) {
                            continue;
                        }

                        printers.computeIfAbsent(visited.size() % numSplits, k -> createPrinter(outputDir, k))
                                .printRecord(record);

                        visited.add(imageIdInt);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read metadata", e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to merge metadata", e);
        } finally {
            printers.values().forEach(p -> {
                try {
                    p.close();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to close printer", e);
                }
            });
        }

        log.info("Merged {} results", visited.size());
    }

    private CSVPrinter createPrinter(Path outputDir, int split) {
        try {
            FileWriter out =
                    new FileWriter(outputDir.resolve("dvids-metadata.csv." + split).toFile(), StandardCharsets.UTF_8);
            return new CSVPrinter(out, CSVFormat.DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create printer", e);
        }
    }

    private void saveDate(OkHttpClient client, String apiKey, LocalDate date, Path outputDir) {
        Path outputFile = outputDir.resolve(date.toString() + ".csv");
        if (outputFile.toFile().exists()) {
            log.info("File for date {} already exists, skipping", date);
            return;
        }

        Path tmpFile = outputDir.resolve(date.toString() + ".tmp");

        try (FileWriter out = new FileWriter(tmpFile.toFile(), StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            writeEntries(client, apiKey, printer, date.atStartOfDay().atOffset(ZoneOffset.UTC), Duration.ofDays(1));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write csv values for date: " + date, e);
        }

        try {
            Files.move(tmpFile, outputFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to move tmp file", e);
        }

        log.info("Saved file for date {} at {}", date, outputFile);
    }

    private void writeEntries(
            OkHttpClient client,
            String apiKey,
            CSVPrinter printer,
            OffsetDateTime start,
            Duration duration) {
        HttpUrl url = forRequest(apiKey, start, duration).build();
        SearchResult result = getSearchResult(client, url);
        int totalResults = result.pageInfo().totalResults();
        log.info("Got {} results for start {} and duration {}", totalResults, start, duration);

        if (totalResults == 1000) {
            log.warn("Truncated results. Trying to get smaller window for start {} and duration {}",
                    start, duration);
            Duration newDuration = duration.dividedBy(2);
            writeEntries(client, apiKey, printer, start, newDuration);
            writeEntries(client, apiKey, printer, start.plus(newDuration), newDuration);
        } else {
            printResults(printer, result.results());
            int offset = result.pageInfo().resultsPerPage();
            int page = 2;

            while (offset < totalResults) {
                HttpUrl pageUrl = forRequest(apiKey, start, duration)
                        .addQueryParameter("page", Integer.toString(page))
                        .build();
                SearchResult pageResult = getSearchResult(client, pageUrl);
                printResults(printer, pageResult.results());

                offset += pageResult.pageInfo().resultsPerPage();
                page++;
            }
        }
    }

    private void printResults(CSVPrinter printer, List<Result> results) {
        results.forEach(result -> {
            try {
                printer.print(result.id());
                printer.print(result.aspectRatio());
                printer.print(result.branch());
                printer.print(result.credit().orElse(null));
                printer.print(result.category().orElse(null));
                printer.print(result.city());
                printer.print(result.country().orElse(null));
                printer.print(result.keywords().orElse(null));
                printer.print(result.date());
                printer.print(result.datePublished());
                printer.print(result.height());
                printer.print(result.rating().isPresent() ? result.rating().getAsDouble() : null);
                printer.print(result.shortDescription());
                printer.print(result.state().orElse(null));
                printer.print(result.thumbHeight().isPresent() ? result.thumbHeight().getAsInt() : null);
                printer.print(result.thumbWidth().isPresent() ? result.thumbWidth().getAsInt() : null);
                printer.print(result.thumbnail().orElse(null));
                printer.print(result.timestamp());
                printer.print(result.title());
                printer.print(result.unitName());
                printer.print(result.url());
                printer.print(result.width());

                printer.println();
            } catch (IOException e) {
                throw new RuntimeException("Failed to write result " + result, e);
            }
        });
    }

    private HttpUrl.Builder forRequest(String apiKey, OffsetDateTime start, Duration duration) {
        return HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("type", "image")
                .addQueryParameter("prettyprint", "0")
                .addQueryParameter("short_description_length", "300")
                .addQueryParameter("from_publishdate", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start))
                .addQueryParameter(
                        "to_publishdate",
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start.plus(duration)));
    }

    private SearchResult getSearchResult(OkHttpClient client, HttpUrl url) {
        return getSearchResult(client, url, 1);
    }

    private SearchResult getSearchResult(OkHttpClient client, HttpUrl url, int attempt) {
        Request request = new Request.Builder().url(url).build();
        log.info("Requesting url {}", url);
        try (Response response = client.newCall(request).execute()) {
            return parseSearchResult(response);
        } catch (IOException | RuntimeException e) {
            if (attempt < 3) {
                log.error("Failed to execute request to url: {}. Retrying...", url, e);
                return getSearchResult(client, url, attempt + 1);
            } else {
                throw new RuntimeException("Failed to execute request to url: " + url, e);
            }
        }
    }

    private SearchResult parseSearchResult(Response response) {
        String responseString = getResponseString(response);

        try {
            return MAPPER.readValue(responseString, SearchResult.class);
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
        new MetadataScraper().run(args);
    }
}
