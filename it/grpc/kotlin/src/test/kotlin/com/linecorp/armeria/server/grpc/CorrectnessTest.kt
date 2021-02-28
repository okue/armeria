/*
 * Copyright (c) 2021 LINE Corporation. All rights reserved.
 * LINE Corporation PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.linecorp.armeria.server.grpc

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.grpc.kotlin.Hello
import com.linecorp.armeria.grpc.kotlin.HelloServiceGrpcKt
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.CompressorRegistry
import io.grpc.DecompressorRegistry
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerMethodDefinition
import io.grpc.Status
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.junit.jupiter.api.Test

@ModelCheckingCTest
class CorrectnessTest {
    @Suppress("UNCHECKED_CAST")
    private val def = TestService().bindService().getMethod(TARGET_METHOD)
        as ServerMethodDefinition<Hello.HelloRequest, Hello.HelloReply>

    private val req = HttpRequest.of(HttpMethod.POST, "/$TARGET_METHOD")
    private val call = ArmeriaServerCall(
        req,
        def.methodDescriptor,
        CompressorRegistry.newEmptyInstance(),
        DecompressorRegistry.emptyInstance(),
        HttpResponse.streaming(),
        1000,
        1000,
        ServiceRequestContext.of(req),
        GrpcSerializationFormats.PROTO,
        null,
        false,
        false,
        ResponseHeaders.of(200),
        null
    )
    private val listener: ServerCall.Listener<Hello.HelloRequest> =
        def.serverCallHandler.startCall(call.toWrapper(), Metadata())

    init {
        call.setListener(listener)
        call.startDeframing()
        listener.onReady()
    }

    @Test
    fun run() {
        LinChecker.check(this::class.java)
    }

    @Operation
    fun a() {
        listener.onMessage(Hello.HelloRequest.getDefaultInstance())
    }

    @Operation
    fun b() {
        listener.onHalfClose()
    }

    companion object {
        private const val TARGET_METHOD = "com.linecorp.armeria.grpc.kotlin.HelloService/ShortBlockingHello"
    }
}

private class ArmeriaServerCallWrapper<Req, Res>(
    private val delegate: ArmeriaServerCall<Req, Res>,
) : ServerCall<Req, Res>() {
    private val state = mutableListOf<Int>()

    override fun request(numMessages: Int) {
        delegate.request(numMessages)
    }

    override fun sendHeaders(headers: Metadata) {
        delegate.sendHeaders(headers)
    }

    override fun sendMessage(message: Res) {
        state.add(0)
        delegate.sendMessage(message)
    }

    override fun close(status: Status, trailers: Metadata) {
        state.add(1)
        delegate.close(status, trailers)
    }

    override fun isCancelled(): Boolean = delegate.isCancelled

    override fun getMethodDescriptor(): MethodDescriptor<Req, Res> = delegate.methodDescriptor
}

private fun <Req, Res> ArmeriaServerCall<Req, Res>.toWrapper() = ArmeriaServerCallWrapper(this)

private class TestService : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {
    override suspend fun shortBlockingHello(request: Hello.HelloRequest): Hello.HelloReply {
        return Hello.HelloReply.getDefaultInstance()
    }
}
