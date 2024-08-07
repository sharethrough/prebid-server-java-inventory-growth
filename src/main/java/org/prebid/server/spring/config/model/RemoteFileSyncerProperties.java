package org.prebid.server.spring.config.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@Data
@NoArgsConstructor
public class RemoteFileSyncerProperties {

    @NotBlank
    private String downloadUrl;

    @NotBlank
    private String saveFilepath;

    @NotBlank
    private String tmpFilepath;

    @Min(1)
    private Integer retryCount;

    @Min(1)
    private Long retryIntervalMs;

    private ExponentialBackoffProperties retry;

    @NotNull
    @Min(1)
    private Long timeoutMs;

    @NotNull
    private Long updateIntervalMs;

    @NotNull
    private HttpClientProperties httpClient;
}
