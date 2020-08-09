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

package com.linecorp.armeria.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.internal.common.RequestContextUtil
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.ProducesJson
import com.linecorp.armeria.server.annotation.StatusCode
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class SuspendingAnnotatedServiceTest {

    @Test
    fun test_normal() {
        val result = get("/kotlinDispatcher/foo?a=aaa&b=100")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("""{"a":"aaa","b":100}""")
    }

    @Test
    fun test_exceptionHandler() {
        val result = get("/kotlinDispatcher/bar")
        assertThat(result.status().code()).isEqualTo(500)
        assertThat(result.contentUtf8()).isEqualTo("handled error")
    }

    @Test
    fun test_noContent() {
        val result = delete("/kotlinDispatcher/baz")
        assertThat(result.status().code()).isEqualTo(204)
    }

    @Test
    fun test_dispatchEventLoop() {
        val result = get("/armeriaDispatcher/qux")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    companion object {
        private val log = LoggerFactory.getLogger(SuspendingAnnotatedServiceTest::class.java)

        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(serverBuilder: ServerBuilder) {
                serverBuilder
                    .annotatedServiceExtensions(
                        emptyList(),
                        listOf(customJacksonResponseConverterFunction()),
                        listOf(exceptionHandlerFunction())
                    )
                    .annotatedService("/kotlinDispatcher", object {
                        @Get("/foo")
                        @ProducesJson
                        suspend fun foo(@Param("a") a: String, @Param("b") b: Int): FooResponse {
                            RequestContext.current<ServiceRequestContext>()
                            withContext(Dispatchers.IO) {
                                RequestContext.current<ServiceRequestContext>()
                                withContext(Dispatchers.Default) {
                                    RequestContext.current<ServiceRequestContext>()
                                }
                            }
                            RequestContext.current<ServiceRequestContext>()
                            return FooResponse(a = a, b = b)
                        }

                        @Get("/bar")
                        suspend fun bar(): HttpResponse {
                            RequestContext.current<ServiceRequestContext>()
                            throw RuntimeException()
                        }

                        @Delete("/baz")
                        @StatusCode(204)
                        suspend fun baz(): Unit? {
                            RequestContext.current<ServiceRequestContext>()
                            return null
                        }
                    })
                    .decoratorUnder("/kotlinDispatcher", CoroutineContextService.newDecorator { ctx ->
                        ArmeriaRequestContext(ctx) + Dispatchers.Default
                    })
                    .annotatedService("/armeriaDispatcher", object {
                        @Get("/qux")
                        suspend fun qux(): String {
                            assertThat(
                                RequestContext.current<ServiceRequestContext>().eventLoop().inEventLoop()
                            ).isTrue()
                            withContext(CoroutineName("test")) {
                                assertThat(
                                    RequestContext.current<ServiceRequestContext>().eventLoop().inEventLoop()
                                ).isTrue()
                            }
                            return "OK"
                        }
                    })
                    .decoratorUnder("/armeriaDispatcher", CoroutineContextService.newDecorator { ctx ->
                        ctx.eventLoop().asCoroutineDispatcher()
                    })
            }
        }

        private fun customJacksonResponseConverterFunction(): JacksonResponseConverterFunction {
            val objectMapper = ObjectMapper()
            objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            return JacksonResponseConverterFunction(objectMapper)
        }

        private fun exceptionHandlerFunction() = ExceptionHandlerFunction { _, _, cause ->
            log.info(cause.message, cause)
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "handled error")
        }

        private fun get(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri())
            return webClient.get(path).aggregate().join()
        }

        private fun delete(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri())
            return webClient.delete(path).aggregate().join()
        }

        private data class FooResponse(val a: String, val b: Int)
    }
}

/**
 * Propagates [ServiceRequestContext] over coroutines.
 */
class ArmeriaRequestContext(
    private val requestContext: ServiceRequestContext?
) : ThreadContextElement<ServiceRequestContext?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ArmeriaRequestContext>

    override fun updateThreadContext(context: CoroutineContext): ServiceRequestContext? {
        if (requestContext == null) return null

        return RequestContextUtil.getAndSet(requestContext)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ServiceRequestContext?) {
        if (oldState != null) {
            RequestContextUtil.getAndSet<ServiceRequestContext>(oldState)
        }
    }
}
