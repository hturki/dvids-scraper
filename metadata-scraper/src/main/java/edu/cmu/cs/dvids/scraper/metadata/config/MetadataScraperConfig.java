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

package edu.cmu.cs.dvids.scraper.metadata.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import java.time.LocalDate;

public final class MetadataScraperConfig extends Configuration {

    @JsonProperty("api-key")
    private String apiKey;

    @JsonProperty("earliest-date")
    private LocalDate earliestDate;

    @JsonProperty("latest-date")
    private LocalDate latestDate;

    @JsonProperty("output-dir")
    private String outputDir;

    @JsonProperty("num-splits")
    private int numSplits;

    public String apiKey() {
        return apiKey;
    }

    public LocalDate earliestDate() {
        return earliestDate;
    }

    public LocalDate latestDate() {
        return latestDate;
    }

    public String outputDir() {
        return outputDir;
    }

    public int numSplits() {
        return numSplits;
    }

}
