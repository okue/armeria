/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.server.grpc.GrpcServiceBuilder.toGrpcStatusFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.grpc.ArmeriaGrpcStatus;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.grpc.testing.MetricsServiceGrpc.MetricsServiceImplBase;
import com.linecorp.armeria.grpc.testing.ReconnectServiceGrpc.ReconnectServiceImplBase;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A2Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A3Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B2Exception;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.Status.Code;

class GrpcServiceBuilderTest {

    @Test
    void mixExceptionMappingAndGrpcStatusFunction() {
        final Function<Throwable, Status> fun1 = cause -> Status.PERMISSION_DENIED;
        assertThatThrownBy(() -> GrpcService.builder()
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                                            .exceptionMapping(fun1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        assertThatThrownBy(() -> GrpcService.builder()
                                            .exceptionMapping(fun1)
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addExceptionMapping() and exceptionMapping() are mutually exclusive.");

        final GrpcStatusFunction fun2 = cause -> ArmeriaGrpcStatus.of(Status.PERMISSION_DENIED);
        assertThatThrownBy(() -> GrpcService.builder()
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                                            .exceptionMapping(fun2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        assertThatThrownBy(() -> GrpcService.builder()
                                            .exceptionMapping(fun2)
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addExceptionMapping() and exceptionMapping() are mutually exclusive.");
    }

    @Test
    void duplicatedExceptionMappings() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED,
                                               null);

        assertThatThrownBy(() -> {
            GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.UNIMPLEMENTED,
                                                   null);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");

        assertThatThrownBy(() -> {
            GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.UNIMPLEMENTED,
                                                   throwable -> new Metadata());
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");
    }

    @Test
    void sortExceptionMappings() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED,
                                               null);
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class, Status.UNIMPLEMENTED,
                                               null);

        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class, A1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B1Exception.class, Status.UNAUTHENTICATED,
                                               throwable -> new Metadata());
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A3Exception.class, Status.UNAUTHENTICATED,
                                               throwable -> new Metadata());
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B2Exception.class, Status.NOT_FOUND, null);
        assertThat(exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B2Exception.class,
                                 B1Exception.class);

        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        ArmeriaGrpcStatus armeriaGrpcStatus = GrpcStatus.fromThrowable(statusFunction, new A3Exception());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.UNAUTHENTICATED);

        armeriaGrpcStatus = GrpcStatus.fromThrowable(statusFunction, new A2Exception());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.UNIMPLEMENTED);

        armeriaGrpcStatus = GrpcStatus.fromThrowable(statusFunction, new A1Exception());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);

        armeriaGrpcStatus = GrpcStatus.fromThrowable(statusFunction, new B2Exception());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.NOT_FOUND);

        armeriaGrpcStatus = GrpcStatus.fromThrowable(statusFunction, new B1Exception());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.UNAUTHENTICATED);
    }

    @Test
    void mapStatus() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class, Status.PERMISSION_DENIED,
                                               null);
        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        for (Throwable ex : ImmutableList.of(new A2Exception(), new A3Exception())) {
            final Status status = Status.UNKNOWN.withCause(ex);
            final ArmeriaGrpcStatus armeriaGrpcStatus =
                    GrpcStatus.fromStatusFunction(statusFunction, status, new Metadata());
            assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.PERMISSION_DENIED);
            assertThat(armeriaGrpcStatus.getCause()).isEqualTo(ex);
            assertThat(armeriaGrpcStatus.getMetadata().keys()).isEmpty();
        }

        final Status status = Status.DEADLINE_EXCEEDED.withCause(new A1Exception());
        final ArmeriaGrpcStatus armeriaGrpcStatus =
                GrpcStatus.fromStatusFunction(statusFunction, status, new Metadata());
        assertThat(armeriaGrpcStatus.get()).isSameAs(status);
        assertThat(armeriaGrpcStatus.getMetadata().keys()).isEmpty();
    }

    @Test
    void mapStatusAndMetadata() {
        final LinkedList<Map.Entry<Class<? extends Throwable>, GrpcStatusFunction>> exceptionMappings =
                new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B1Exception.class, Status.ABORTED,
                                               throwable -> {
                                                   final Metadata metadata = new Metadata();
                                                   metadata.put(TEST_KEY, throwable.getClass().getSimpleName());
                                                   return metadata;
                                               });
        final GrpcStatusFunction statusFunction = toGrpcStatusFunction(exceptionMappings);

        final Status status = Status.UNKNOWN.withCause(new B1Exception());

        ArmeriaGrpcStatus armeriaGrpcStatus =
                GrpcStatus.fromStatusFunction(statusFunction, status, new Metadata());
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.ABORTED);
        assertThat(armeriaGrpcStatus.getMetadata().get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(armeriaGrpcStatus.getMetadata().keys()).containsOnly(TEST_KEY.name());

        final Metadata metadata = new Metadata();
        metadata.put(TEST_KEY2, "test");
        armeriaGrpcStatus = GrpcStatus.fromStatusFunction(statusFunction, status, metadata);
        assertThat(armeriaGrpcStatus.getCode()).isEqualTo(Code.ABORTED);
        assertThat(armeriaGrpcStatus.getMetadata().get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(armeriaGrpcStatus.getMetadata().keys()).containsOnly(TEST_KEY.name(), TEST_KEY2.name());
    }

    @Test
    void addServices() {
        final BindableService metricsService = new MetricsServiceImpl();
        final BindableService reconnectService = new ReconnectServiceImpl();

        final List<String> serviceNames =
                ImmutableList.of("armeria.grpc.testing.MetricsService",
                                 "armeria.grpc.testing.ReconnectService");

        final GrpcService grpcService1 =
                GrpcService.builder().addServices(metricsService, reconnectService).build();

        final GrpcService grpcService2 =
                GrpcService.builder().addServices(ImmutableList.of(metricsService, reconnectService)).build();

        final GrpcService grpcService3 =
                GrpcService.builder()
                           .addServiceDefinitions(
                                   ServerInterceptors.intercept(metricsService, new DummyInterceptor()),
                                   ServerInterceptors.intercept(reconnectService, new DummyInterceptor())
                           )
                           .build();

        final GrpcService grpcService4 =
                GrpcService
                        .builder()
                        .addServiceDefinitions(
                                ImmutableList.of(
                                        ServerInterceptors.intercept(metricsService, new DummyInterceptor()),
                                        ServerInterceptors.intercept(reconnectService, new DummyInterceptor())
                                )
                        )
                        .build();

        for (GrpcService s : ImmutableList.of(grpcService1, grpcService2, grpcService3, grpcService4)) {
            assertThat(s.services().stream().map(it -> it.getServiceDescriptor().getName()))
                    .containsExactlyInAnyOrderElementsOf(serviceNames);
        }
    }

    private static class MetricsServiceImpl extends MetricsServiceImplBase {}

    private static class ReconnectServiceImpl extends ReconnectServiceImplBase {}

    private static class DummyInterceptor implements ServerInterceptor {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {
            return next.startCall(call, headers);
        }
    }

    private static final Metadata.Key<String> TEST_KEY =
            Metadata.Key.of("test_key", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> TEST_KEY2 =
            Metadata.Key.of("test_key2", Metadata.ASCII_STRING_MARSHALLER);
}
