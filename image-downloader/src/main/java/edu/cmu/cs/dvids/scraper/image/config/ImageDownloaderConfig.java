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

package edu.cmu.cs.dvids.scraper.image.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;

public final class ImageDownloaderConfig extends Configuration {

    @JsonProperty("api-key")
    private String apiKey;

    @JsonProperty("input-file")
    private String inputFile;

    @JsonProperty("output-dir")
    private String outputDir;

    public String apiKey() {
        return apiKey;
    }

    public String outputDir() {
        return outputDir;
    }

    public String inputFile() {
        return inputFile;
    }

}
