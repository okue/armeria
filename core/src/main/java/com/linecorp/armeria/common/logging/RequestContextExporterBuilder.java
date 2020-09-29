/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.logging.RequestContextExporter.ExportEntry;

import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;

/**
 * Builds a new {@link RequestContextExporter}.
 */
public final class RequestContextExporterBuilder {

    static final String PREFIX_ATTRS = "attrs.";
    private static final String ATTR_NAMESPACE = "attr:";
    private static final String PREFIX_REQ_HEADERS = "req.headers.";
    private static final String PREFIX_RES_HEADERS = "res.headers.";

    @Nullable
    private String exportPrefix = null;
    private final Set<ExportEntry<BuiltInProperty>> builtIns = new HashSet<>();
    private final Set<ExportEntry<AttributeKey<?>>> attrs = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> reqHeaders = new HashSet<>();
    private final Set<ExportEntry<AsciiString>> resHeaders = new HashSet<>();

    RequestContextExporterBuilder() {}

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The {@link BuiltInProperty#key} will be used for the export key.
     */
    public RequestContextExporterBuilder builtIn(BuiltInProperty property) {
        return builtIn(property, property.key);
    }

    /**
     * Adds the specified {@link BuiltInProperty} to the export list.
     * The specified {@code alias} will be used for the export key.
     */
    public RequestContextExporterBuilder builtIn(BuiltInProperty property, String alias) {
        requireNonNull(property, "property");
        requireNonNull(alias, "alias");
        builtIns.add(new ExportEntry<>(property, alias));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     */
    public RequestContextExporterBuilder attr(String alias, AttributeKey<?> attrKey) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        attrs.add(new ExportEntry<>(attrKey, alias));
        return this;
    }

    /**
     * Adds the specified {@link AttributeKey} to the export list.
     * The specified {@code alias} is used for the export key.
     *
     * @param alias the alias of the attribute to export
     * @param attrKey the key of the attribute to export
     * @param stringifier the {@link Function} that converts the attribute value into a {@link String}
     */
    public RequestContextExporterBuilder attr(String alias, AttributeKey<?> attrKey,
                                              Function<?, String> stringifier) {
        requireNonNull(alias, "alias");
        requireNonNull(attrKey, "attrKey");
        requireNonNull(stringifier, "stringifier");
        attrs.add(new ExportEntry<>(attrKey, alias, stringifier));
        return this;
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     */
    public RequestContextExporterBuilder requestHeader(CharSequence headerName) {
        final AsciiString key = toHeaderName(requireNonNull(headerName, "headerName"));
        return addRequestHeader(key, PREFIX_REQ_HEADERS + key);
    }

    /**
     * Adds the specified HTTP request header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder requestHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        return addRequestHeader(toHeaderName(headerName), alias);
    }

    private RequestContextExporterBuilder addRequestHeader(AsciiString headerKey, String alias) {
        reqHeaders.add(new ExportEntry<>(headerKey, alias));
        return this;
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     */
    public RequestContextExporterBuilder responseHeader(CharSequence headerName) {
        final AsciiString key = toHeaderName(requireNonNull(headerName, "headerName"));
        return addResponseHeader(key, PREFIX_RES_HEADERS + key);
    }

    /**
     * Adds the specified HTTP response header name to the export list.
     * The specified {@code alias} is used for the export key.
     */
    public RequestContextExporterBuilder responseHeader(CharSequence headerName, String alias) {
        requireNonNull(headerName, "headerName");
        requireNonNull(alias, "alias");
        return addResponseHeader(toHeaderName(headerName), alias);
    }

    private RequestContextExporterBuilder addResponseHeader(AsciiString headerKey, String alias) {
        resHeaders.add(new ExportEntry<>(headerKey, alias));
        return this;
    }

    private static AsciiString toHeaderName(CharSequence name) {
        return HttpHeaderNames.of(requireNonNull(name, "name").toString());
    }

    /**
     * Adds the property represented by the specified key pattern to the export list. Please refer to the
     * <a href="https://armeria.dev/docs/advanced-logging">Logging contextual information</a>
     * in order to learn how to specify a key pattern.
     */
    public RequestContextExporterBuilder keyPattern(String keyPattern) {
        requireNonNull(keyPattern, "keyPattern");

        final int exportKeyPos = keyPattern.indexOf('=');

        if (keyPattern.contains(BuiltInProperty.WILDCARD_STR)) {
            if (exportKeyPos > 0) {
                throw new IllegalArgumentException(
                        "A custom export key is unsupported for the wildcard: " + keyPattern);
            }
            BuiltInProperty.findByKeyPattern(keyPattern)
                           .stream().map(prop -> new ExportEntry<>(prop, prop.key))
                           .forEach(builtIns::add);
            return this;
        }

        String exportKey = null;
        if (exportKeyPos > 0) {
            exportKey = keyPattern.substring(0, exportKeyPos);
            keyPattern = keyPattern.substring(exportKeyPos + 1);
        }

        final BuiltInProperty property = BuiltInProperty.findByKey(keyPattern);
        if (property != null) {
            builtIns.add(new ExportEntry<>(property, exportKey != null ? exportKey : property.key));
            return this;
        }

        if (keyPattern.startsWith(PREFIX_ATTRS) || keyPattern.startsWith(ATTR_NAMESPACE)) {
            final ExportEntry<AttributeKey<?>> attrExportEntry = parseAttrPattern(keyPattern, exportKey);
            attrs.add(attrExportEntry);
            return this;
        }

        if (keyPattern.startsWith(PREFIX_REQ_HEADERS)) {
            if (exportKey == null) {
                requestHeader(keyPattern.substring(PREFIX_REQ_HEADERS.length()));
            } else {
                requestHeader(keyPattern.substring(PREFIX_REQ_HEADERS.length()), exportKey);
            }
            return this;
        }

        if (keyPattern.startsWith(PREFIX_RES_HEADERS)) {
            if (exportKey == null) {
                responseHeader(keyPattern.substring(PREFIX_RES_HEADERS.length()));
            } else {
                responseHeader(keyPattern.substring(PREFIX_RES_HEADERS.length()), exportKey);
            }
            return this;
        }

        throw new IllegalArgumentException("unknown key pattern: " + keyPattern);
    }

    private ExportEntry<AttributeKey<?>> parseAttrPattern(String keyPattern, @Nullable String exportKey) {
        final String[] components = keyPattern.split(":");
        if (components.length < 2 || components.length > 3) {
            if (exportKey == null) {
                throw new IllegalArgumentException(
                        "invalid attribute export: " + keyPattern +
                        " (expected: attrs.<alias>:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            } else {
                throw new IllegalArgumentException(
                        "invalid attribute export: " + keyPattern +
                        " (expected: <alias>=attr:<AttributeKey.name>[:<FQCN of Function<?, String>>])");
            }
        }

        if (exportKey == null) {
            exportKey = components[0];
        }
        final AttributeKey<Object> attributeKey = AttributeKey.valueOf(components[1]);
        if (components.length == 3) {
            return new ExportEntry<>(attributeKey, exportKey, newStringifier(keyPattern, components[2]));
        } else {
            return new ExportEntry<>(attributeKey, exportKey);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<?, String> newStringifier(String keyPattern, String className) {
        final Function<?, String> stringifier;
        try {
            stringifier = (Function<?, String>)
                    Class.forName(className, true, getClass().getClassLoader())
                         .getDeclaredConstructor()
                         .newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to instantiate a stringifier function: " +
                                               keyPattern, e);
        }
        return stringifier;
    }

    public void setExportPrefix(String exportPrefix) {
        requireNonNull(exportPrefix, "exportPrefix");
        checkArgument(!exportPrefix.isEmpty(), "exportPrefix must not be empty");

        this.exportPrefix = exportPrefix;
    }

    /**
     * Returns a newly-created {@link RequestContextExporter} instance.
     */
    public RequestContextExporter build() {
        if (exportPrefix == null) {
            return new RequestContextExporter(builtIns, attrs, reqHeaders, resHeaders);
        } else {
            return new RequestContextExporter(
                    ExportEntry.withPrefix(builtIns, exportPrefix),
                    ExportEntry.withPrefix(attrs, exportPrefix),
                    ExportEntry.withPrefix(reqHeaders, exportPrefix),
                    ExportEntry.withPrefix(resHeaders, exportPrefix)
            );
        }
    }
}
