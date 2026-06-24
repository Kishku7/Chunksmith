package com.kishku7.chunksmith.integration;

import com.kishku7.chunksmith.platform.Border;

public interface BorderIntegration extends Integration {
    boolean hasBorder(String world);

    Border getBorder(String world);
}
