/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.container;

import java.util.concurrent.ExecutionException;

/**
 * Utility class.
 */
public class Utils {

    /**
     * Polling with timeout utility function, accept a boolean supplier that throws Exception.
     * It'll retry until the retryCount reached or there's timeout.
     *
     * @param supplier
     * @param waitTimeMs
     * @param timeoutMs -1 means no limitation, 0 means no wait.
     * @return true if success, false if timeout reached.
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public static boolean pollingWithTimeout(CheckedBooleanSupplier supplier, long waitTimeMs, long timeoutMs)
        throws InterruptedException, ExecutionException  {
        long start = System.currentTimeMillis();
        // TODO: default timeout MS should be provided by upper layer.
        if (timeoutMs < 0) {
            waitTimeMs = 1000;
        }
        try {
            while (timeoutMs <= 0 || System.currentTimeMillis() - start < timeoutMs) {
                boolean success = supplier.getAsBoolean();
                if (success) {
                    return true;
                }
                long remain = timeoutMs - (System.currentTimeMillis() - start);
                if (timeoutMs == 0) {
                    break;
                }
                Thread.sleep(timeoutMs == -1 || waitTimeMs < remain || remain <= 0 ? waitTimeMs : remain);
            }
            return false;
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
    }

    /**
     * A functional interface that return boolean value and throw Exception if any error.
     */
    @FunctionalInterface
    public interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
