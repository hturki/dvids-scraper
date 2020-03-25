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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(as = ImmutableResult.class)
public interface Result {

    @JsonProperty("aspect_ratio")
    String aspectRatio();

    String branch();

    Optional<String> credit();

    Optional<String> category();

    String city();

    Optional<String> country();

    Optional<String> keywords();

    String date();

    @JsonProperty("date_published")
    String datePublished();

    int height();

    String id();

    OptionalDouble rating();

    @JsonProperty("short_description")
    String shortDescription();

    Optional<String> state();

    @JsonProperty("thumb_height")
    OptionalInt thumbHeight();

    @JsonProperty("thumb_width")
    OptionalInt thumbWidth();

    Optional<String> thumbnail();

    String timestamp();

    String title();

    String type();

    @JsonProperty("unit_name")
    String unitName();

    String url();

    int width();

    // Deprecated and will be removed in later version of api
    Optional<String> publishdate();

}
