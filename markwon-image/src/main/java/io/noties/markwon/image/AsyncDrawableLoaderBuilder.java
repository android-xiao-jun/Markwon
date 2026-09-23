package io.noties.markwon.image;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.image.data.DataUriSchemeHandler;
import io.noties.markwon.image.gif.GifMediaDecoder;
import io.noties.markwon.image.gif.GifSupport;
import io.noties.markwon.image.network.NetworkSchemeHandler;
import io.noties.markwon.image.svg.SvgMediaDecoder;
import io.noties.markwon.image.svg.SvgSupport;

public class AsyncDrawableLoaderBuilder {

    ExecutorService executorService;
    final Map<String, SchemeHandler> schemeHandlers = new HashMap<>(3);
    final Map<String, MediaDecoder> mediaDecoders = new HashMap<>(3);
    MediaDecoder defaultMediaDecoder;
    ImagesPlugin.PlaceholderProvider placeholderProvider;
    ImagesPlugin.ErrorHandler errorHandler;

    boolean isBuilt;

    public AsyncDrawableLoaderBuilder() {

        // @since 4.0.0
        // okay, let's add supported schemes at the start, this would be : data-uri and default network
        // we should not use file-scheme as it's a bit complicated to assume file usage (lack of permissions)
        addSchemeHandler(DataUriSchemeHandler.create());
        addSchemeHandler(NetworkSchemeHandler.create());

        // add SVG and GIF, but only if they are present in the class-path
        if (SvgSupport.hasSvgSupport()) {
            addMediaDecoder(SvgMediaDecoder.create());
        }

        if (GifSupport.hasGifSupport()) {
            addMediaDecoder(GifMediaDecoder.create());
        }

        defaultMediaDecoder = DefaultMediaDecoder.create();
    }

    public void executorService(@NonNull ExecutorService executorService) {
        checkState();
        this.executorService = executorService;
    }

    public void addSchemeHandler(@NonNull SchemeHandler schemeHandler) {
        checkState();
        for (String scheme : schemeHandler.supportedSchemes()) {
            schemeHandlers.put(scheme, schemeHandler);
        }
    }

    public void addMediaDecoder(@NonNull MediaDecoder mediaDecoder) {
        checkState();
        for (String type : mediaDecoder.supportedTypes()) {
            mediaDecoders.put(type, mediaDecoder);
        }
    }

    public void defaultMediaDecoder(@Nullable MediaDecoder mediaDecoder) {
        checkState();
        this.defaultMediaDecoder = mediaDecoder;
    }

    public void removeSchemeHandler(@NonNull String scheme) {
        checkState();
        schemeHandlers.remove(scheme);
    }

    public void removeMediaDecoder(@NonNull String contentType) {
        checkState();
        mediaDecoders.remove(contentType);
    }

    /**
     * @since 3.0.0
     */
    public void placeholderProvider(@NonNull ImagesPlugin.PlaceholderProvider placeholderDrawableProvider) {
        checkState();
        this.placeholderProvider = placeholderDrawableProvider;
    }

    /**
     * @since 3.0.0
     */
    public void errorHandler(@NonNull ImagesPlugin.ErrorHandler errorHandler) {
        checkState();
        this.errorHandler = errorHandler;
    }

    @NonNull
    public AsyncDrawableLoader build() {

        checkState();

        isBuilt = true;

        if (executorService == null) {
            executorService = Executors.newCachedThreadPool();
        }

        return new AsyncDrawableLoaderImpl(this);
    }

    private void checkState() {
        if (isBuilt) {
            throw new IllegalStateException("ImagesPlugin has already been configured " +
                    "and cannot be modified any further");
        }
    }
}
