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
import java.util.List;
import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(as = ImmutableSearchResult.class)
public interface SearchResult {

    @JsonProperty("page_info")
    PageInfo pageInfo();

    List<Result> results();

}
