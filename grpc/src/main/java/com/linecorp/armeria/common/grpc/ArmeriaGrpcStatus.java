/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.grpc;

import javax.annotation.Nullable;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Holds {@link Status} and {@link Metadata}.
 */
public class ArmeriaGrpcStatus {

    private final Status status;

    private final Metadata metadata;

    ArmeriaGrpcStatus(Status status, @Nullable Metadata metadata) {
        this.status = status;
        this.metadata = metadata == null ? new Metadata() : metadata;
    }

    /**
     * Returns {@link ArmeriaGrpcStatus}.
     */
    public static ArmeriaGrpcStatus of(Status status) {
        return new ArmeriaGrpcStatus(status, null);
    }

    /**
     * Returns {@link ArmeriaGrpcStatus}.
     */
    public static ArmeriaGrpcStatus of(Status status, Metadata metadata) {
        return new ArmeriaGrpcStatus(status, metadata);
    }

    /**
     * Returns {@link Status}.
     */
    public Status get() {
        return status;
    }

    /**
     * Returns {@link Metadata}.
     */
    public Metadata getMetadata() {
        return metadata;
    }

    /**
     * Returns {@link Code}.
     */
    public Code getCode() {
        return status.getCode();
    }

    /**
     * Returns a cause {@link Throwable} of an error.
     */
    @Nullable
    public Throwable getCause() {
        return status.getCause();
    }

    /**
     * Creates a new instance of {@link ArmeriaGrpcStatus} derived from this instance with the given cause.
     */
    public ArmeriaGrpcStatus withCause(Throwable cause) {
        return ArmeriaGrpcStatus.of(status.withCause(cause), metadata);
    }
}
