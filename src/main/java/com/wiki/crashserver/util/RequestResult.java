package com.wiki.crashserver.util;

import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.ByteBuffer;

public class RequestResult {
    private final HttpResponseStatus status;
    private final String contentType;
    private final ByteBuffer responseContent;

    public RequestResult(String contentType, HttpResponseStatus status, ByteBuffer responseContent) {
        this.contentType = contentType;
        this.status = status;
        this.responseContent = responseContent;
    }

    public String getContentType() {
        return contentType;
    }

    public ByteBuffer getResponseContent() {
        return responseContent;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }
}
