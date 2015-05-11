package com.wiki.crashserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki.crashserver.util.EditResult;
import com.wiki.crashserver.util.RequestResult;
import com.wiki.crashserver.util.diff_match_patch;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.commons.io.IOUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.event.Event;
import reactor.function.Function;
import reactor.tuple.Tuple2;

import javax.annotation.PostConstruct;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class ResourceHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final diff_match_patch DIFF_MATCH_PATCH = new diff_match_patch();

    /*
        The copy of the resource we are passing out to normal readers.
        It's an immutable ByteBuffer, so we don't need any synchronization on it,
        simple volatile is enough.
     */
    private volatile ByteBuffer resource;

    /*
        The copy of the resource which editors work on. It's provided to editors
        and patched by them. Our patching lib should work directly in memory on
        this buffer (see below), so we use a ReadWriteLock on it and pass out copies.
     */
    private volatile ByteBuffer resourceShadow;
    private final ReadWriteLock shadowLock = new ReentrantReadWriteLock();

    private volatile Map<Tuple2<String, HttpMethod>, Function<Event<ByteBuf>, RequestResult>> requestMapper;

    @PostConstruct
    public void setUp() throws Exception {
        byte[] resourceBytes = IOUtils.toByteArray(ResourceHandler.class.getResource("/article.html"));
        byte[] resourceBytesCopy = Arrays.copyOf(resourceBytes, resourceBytes.length);
        resourceShadow = ByteBuffer.wrap(resourceBytesCopy);
        resource = ByteBuffer.wrap(resourceBytes).asReadOnlyBuffer();

        requestMapper = new HashMap<>();
        requestMapper.put(Tuple2.of("/crash", HttpMethod.GET), this::getResource);
        requestMapper.put(Tuple2.of("/crashedit", HttpMethod.GET), this::getResourceShadow);
        requestMapper.put(Tuple2.of("/crashedit", HttpMethod.POST), this::editResource);
    }

    public Map<Tuple2<String, HttpMethod>, Function<Event<ByteBuf>, RequestResult>> getRequestMapper() {
        return requestMapper;
    }

    public boolean canHandleRequest(FullHttpRequest request) {
        return requestMapper.keySet()
                .stream()
                .anyMatch(t -> request.getUri().endsWith(t.getT1())
                        && request.getMethod().equals(t.getT2()));
    }

    public RequestResult getResource(Event<ByteBuf> reqContent) {
        if (fib(34) > 0) {
            return new RequestResult("text/html", HttpResponseStatus.OK, resource);
        }
        throw new AssertionError();
    }

    public RequestResult getResourceShadow(Event<ByteBuf> reqContent) {
        if (fib(34) > 0) {
            byte[] localResource;
            try {
                shadowLock.readLock().lock();
                localResource = Arrays.copyOf(resourceShadow.array(), resourceShadow.array().length);
            } finally {
                shadowLock.readLock().unlock();
            }
            return new RequestResult("text/html", HttpResponseStatus.OK, ByteBuffer.wrap(localResource));
        }
        throw new AssertionError();
    }

    public RequestResult editResource(Event<ByteBuf> reqContent) {
        /*
            google diff_match_patch is the only lib I've found for patching.

            The problem with it is that it's not supposed to work for HTML,
            but also that it's operation on strings, so many unnecessary
            content copies happen. The perfect patching lib would work on byte
            arrays provided to it directly, so I've set up the locking as if we've
            had this perfect patching lib
         */
        try {
            List<diff_match_patch.Patch> patches = DIFF_MATCH_PATCH
                    .patch_fromText(reqContent.getData().toString(StandardCharsets.UTF_8));
            Object[] result;
            try {
                shadowLock.writeLock().lock();
                result = DIFF_MATCH_PATCH.patch_apply((LinkedList<diff_match_patch.Patch>) patches,
                        new String(resourceShadow.array(), StandardCharsets.UTF_8));
                resourceShadow = ByteBuffer.wrap(((String) result[0]).getBytes(StandardCharsets.UTF_8));
            } finally {
                shadowLock.writeLock().unlock();
            }
            List<EditResult.PatchResult> patchResults = new ArrayList<>();
            boolean[] patchingResults = (boolean[])result[1];
            for (int i = 0; i < patchingResults.length; i++) {
                patchResults.add(new EditResult.PatchResult(patches.get(i).toString(), patchingResults[i]));
            }
            ByteBuffer returnValue = ByteBuffer.wrap(MAPPER.writeValueAsBytes(new EditResult(patchResults)));
            return new RequestResult("application/json", HttpResponseStatus.OK, returnValue);
        } catch (Exception e) {
            e.printStackTrace();
            return new RequestResult("text/plain", HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    ByteBuffer.wrap(e.getMessage().getBytes(StandardCharsets.UTF_8)));
        }

    }

    /*
        Ordinary readers don't really need to se the most final version of the
        resource, so we can pass them a 'stable copy' which is updated every N seconds.

        The same way we can update the file copy of the resource. Also it's updated when
        the server is shut down. So unless the server crashes we wouldn't loose the edits.
     */
    @Scheduled(fixedDelay = 10000)
    public void syncShadow() {
        byte[] localResource;
        try {
            shadowLock.readLock().lock();
            localResource = Arrays.copyOf(resourceShadow.array(), resourceShadow.array().length);
        } finally {
            shadowLock.readLock().unlock();
        }

        resource = ByteBuffer.wrap(localResource).asReadOnlyBuffer();
        try (FileOutputStream fos = new FileOutputStream(ResourceHandler.class.getResource("/article.html").getPath());
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(localResource);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static int fib(int n) {
        if (n == 1 || n == 2) {
            return 1;
        }
        return fib(n - 1) + fib(n - 2);
    }
}
